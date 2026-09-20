package com.careerai.backend.admin;

import com.careerai.backend.channel.*;
import com.careerai.backend.faq.*;
import com.careerai.backend.semantic.FaqEntryChangedEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.constraints.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.LocalDate;

@Service
public class AdminMutationService {
    private final EntityManager em;
    private final TelegramChannelPostArchiveService archives;
    private final TelegramChannelPostFreshnessService freshness;
    private final TelegramChannelPostRelationService relations;
    private final AdminAuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    public AdminMutationService(EntityManager em,TelegramChannelPostArchiveService archives,
            TelegramChannelPostFreshnessService freshness,TelegramChannelPostRelationService relations,
            AdminAuditService audit,ApplicationEventPublisher events,Clock clock) {
        this.em=em;this.archives=archives;this.freshness=freshness;this.relations=relations;this.audit=audit;this.events=events;this.clock=clock;
    }
    public record PostAction(@NotNull @PositiveOrZero Long revision,@Size(max=2000) String reason) {}
    public record DateConfirmationInput(@NotNull @PositiveOrZero Long revision,@NotNull LocalDate date,
            @NotNull DateBoundaryType boundary,@NotNull ChannelPostDatePurpose purpose,
            @NotBlank @Size(max=2000) String reason) {}
    public record FaqInput(@PositiveOrZero Long revision,@NotBlank @Size(max=100) String category,
            @NotBlank @Pattern(regexp="[a-z0-9][a-z0-9-]{0,149}") String slug,
            @NotBlank @Size(max=2000) String question,@NotBlank @Size(max=4000) String shortAnswer,
            @NotBlank @Size(max=20000) String fullAnswer,@Size(max=2000) String keywords,
            @Min(0) @Max(100000) int priority,boolean active) {}
    public record RelationInput(@NotNull @PositiveOrZero Long revision,@NotNull TelegramChannelPostRelationType type,
                                @NotBlank @Size(max=2000) String reason) {}
    public record LinkInput(@Positive long sourcePostId,@Positive long targetPostId,
                            @NotNull TelegramChannelPostRelationType type,@NotBlank @Size(max=2000) String reason) {}
    public record MetadataInput(@NotNull @PositiveOrZero Long revision,@NotNull TelegramChannelPostType postType,
            @Size(max=500) String title,@Size(max=500) String company,@Size(max=2000) String technologies,
            @Size(max=255) String levelText,@Size(max=255) String formatText,@Size(max=500) String deadlineText,
            @Size(max=255) String practiceStartText,@Size(max=255) String practiceEndText,
            @Size(max=10000) String summary,boolean relevantForPractice,@NotBlank @Size(max=2000) String reason) {}

    @Transactional
    public void confirmDate(long actor,long postId,DateConfirmationInput input) {
        var post=required(TelegramChannelPost.class,postId);
        check(post.getRevision(),input.revision());
        if(input.date()==null||input.date().getYear()<1900||input.date().getYear()>9999)
            throw new IllegalArgumentException("Укажите полную дату с годом от 1900 до 9999");
        if(input.boundary()==null||input.boundary()==DateBoundaryType.UNSPECIFIED||input.purpose()==null)
            throw new IllegalArgumentException("Подтвердите назначение даты и включается ли последний день");
        requireReason(input.reason());
        if(input.purpose()==ChannelPostDatePurpose.EVENT_DATE&&input.boundary()!=DateBoundaryType.INCLUSIVE)
            throw new IllegalArgumentException("Дата мероприятия включает сам день мероприятия");
        post.setConfirmedDate(input.date());post.setConfirmedDateBoundary(input.boundary());
        post.setConfirmedDatePurpose(input.purpose());post.setConfirmedDateSourceHash(post.dateConfirmationSourceHash());
        post.setDateConfirmedBy(actor);post.setDateConfirmedAt(OffsetDateTime.now(clock));
        post.setDateConfirmationReason(input.reason().strip());
        em.flush();freshness.recalculateOne(postId);
        audit.record(actor,"date-confirm","post",postId,"date="+input.date()+"; boundary="+input.boundary()
                +"; purpose="+input.purpose()+"; "+input.reason().strip());
    }

    @Transactional
    public void revokeDate(long actor,long postId,PostAction input) {
        var post=required(TelegramChannelPost.class,postId);
        check(post.getRevision(),input.revision());requireReason(input.reason());
        String previous="date="+post.getConfirmedDate()+"; purpose="+post.getConfirmedDatePurpose();
        post.clearDateConfirmation();em.flush();freshness.recalculateOne(postId);
        audit.record(actor,"date-revoke","post",postId,previous+"; "+input.reason().strip());
    }

    private static void requireReason(String reason) {
        if(reason==null||reason.isBlank()||reason.length()>2000)
            throw new IllegalArgumentException("Укажите основание подтверждения или отмены (до 2000 символов)");
    }

    @Transactional
    public void metadata(long actor,long postId,MetadataInput input) {
        required(TelegramChannelPost.class,postId);
        var entry=em.createQuery("SELECT m FROM TelegramChannelPostMetadata m WHERE m.post.id=:id",TelegramChannelPostMetadata.class)
                .setParameter("id",postId).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Метаданные не найдены"));
        check(entry.getRevision(),input.revision());
        entry.setPostType(input.postType());entry.setTitle(input.title());entry.setCompany(input.company());
        entry.setTechnologies(input.technologies());entry.setLevelText(input.levelText());entry.setFormatText(input.formatText());
        entry.setDeadlineText(input.deadlineText());entry.setPracticeStartText(input.practiceStartText());entry.setPracticeEndText(input.practiceEndText());
        entry.setSummary(input.summary());entry.setRelevantForPractice(input.relevantForPractice());
        entry.setExtractionStatus(TelegramChannelPostExtractionStatus.SUCCESS);entry.setExtractionError(null);
        entry.setExtractedAt(OffsetDateTime.now(clock));em.flush();freshness.recalculateOne(postId);
        events.publishEvent(new com.careerai.backend.semantic.ChannelPostEligibilityChangedEvent(postId));
        audit.record(actor,"metadata-update","post",postId,input.reason());
    }

    @Transactional
    public void postAction(long actor,long id,String action,PostAction request) {
        var post=required(TelegramChannelPost.class,id);check(post.getRevision(),request.revision());
        switch(action) {
            case "archive" -> archives.archive(id,request.reason());
            case "restore" -> archives.restore(id);
            case "freshness" -> freshness.recalculateOne(id);
            case "extract" -> {
                var metadata=em.createQuery("SELECT m FROM TelegramChannelPostMetadata m WHERE m.post.id=:id",TelegramChannelPostMetadata.class).setParameter("id",id).getResultStream().findFirst()
                        .orElseThrow(()->new IllegalArgumentException("Нет записи метаданных"));
                metadata.setExtractionStatus(TelegramChannelPostExtractionStatus.PENDING);
                metadata.setExtractionError(null);
            }
            default -> throw new IllegalArgumentException("Неизвестное действие");
        }
        audit.record(actor,action,"post",id,request.reason());
    }

    @Transactional
    public long saveFaq(long actor,Long id,FaqInput request) {
        FaqEntry entry=id==null?new FaqEntry():required(FaqEntry.class,id);
        if(id!=null)check(entry.getRevision(),request.revision());
        entry.setCategory(request.category().strip());entry.setSlug(request.slug());entry.setQuestion(request.question().strip());
        entry.setShortAnswer(request.shortAnswer().strip());entry.setFullAnswer(request.fullAnswer().strip());entry.setKeywords(request.keywords());
        entry.setPriority(request.priority());entry.setActive(request.active());entry.setUpdatedAt(OffsetDateTime.now(clock));
        if(id==null)em.persist(entry);em.flush();
        audit.record(actor,id==null?"create":"update","faq",entry.getId(),"slug="+entry.getSlug());
        events.publishEvent(new FaqEntryChangedEvent(entry.getId()));
        return entry.getId();
    }

    @Transactional
    public void confirmRelation(long actor,long id,RelationInput request) {
        var relation=required(TelegramChannelPostRelation.class,id);check(relation.getEntityVersion(),request.revision());
        relations.link(relation.getSourcePost().getId(),relation.getTargetPost().getId(),request.type(),request.reason());
        relation.setRelationOrigin(TelegramChannelPostRelationOrigin.ADMIN_CONFIRMED);
        audit.record(actor,"confirm","relation",id,request.reason());
    }
    @Transactional
    public void removeRelation(long actor,long id,PostAction request) {
        var relation=required(TelegramChannelPostRelation.class,id);check(relation.getEntityVersion(),request.revision());
        if(request.reason()==null||request.reason().isBlank())throw new IllegalArgumentException("Укажите причину удаления связи");
        em.remove(relation);audit.record(actor,"unlink","relation",id,request.reason());
    }
    @Transactional
    public void retryRelation(long actor,long id,PostAction request) {
        var relation=required(TelegramChannelPostRelation.class,id);check(relation.getEntityVersion(),request.revision());
        if(relation.getRelationOrigin()!=TelegramChannelPostRelationOrigin.TELEGRAM_REPLY&&relation.getRelationOrigin()!=TelegramChannelPostRelationOrigin.SYSTEM_BACKFILL)
            throw new IllegalArgumentException("Повторная классификация доступна только для reply-связей; отдельные уточнения проверяйте через кандидатов");
        relation.setClassificationStatus(TelegramChannelPostRelationClassificationStatus.PENDING);
        relation.setClassificationAttemptCount(0);relation.setClassificationNextAttemptAt(OffsetDateTime.now(clock));
        relation.setProcessingStartedAt(null);relation.setClassificationError(null);
        audit.record(actor,"retry","relation",id,"Запрошена повторная классификация");
    }
    @Transactional
    public void link(long actor,LinkInput request) {
        var source=required(TelegramChannelPost.class,request.sourcePostId());
        var target=required(TelegramChannelPost.class,request.targetPostId());
        if(!source.getTelegramChatId().equals(target.getTelegramChatId()))throw new IllegalArgumentException("Публикации должны быть из одного канала");
        if(source.getTelegramMessageId()<=target.getTelegramMessageId())throw new IllegalArgumentException("Уточнение должно быть новее основной публикации");
        var linked=relations.link(source.getId(),target.getId(),request.type(),request.reason());
        audit.record(actor,"link","post",source.getId(),"target="+target.getId()+"; "+request.reason());
    }
    private <T>T required(Class<T> type,long id) {
        T entity=em.find(type,id,LockModeType.PESSIMISTIC_WRITE);
        if(entity==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Запись не найдена");return entity;
    }
    private static void check(long actual,Long expected) {
        if(expected==null||actual!=expected)throw new ResponseStatusException(HttpStatus.CONFLICT,"Запись уже изменилась. Обновите страницу и повторите действие.");
    }
}
