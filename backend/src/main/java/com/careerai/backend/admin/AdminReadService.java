package com.careerai.backend.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.time.Clock;
import java.util.*;

@Service
public class AdminReadService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public AdminReadService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }
    public record PageView(List<Map<String,Object>> items, long total, int page, int size) {}

    public Map<String,Object> overview() {
        var result = new LinkedHashMap<String,Object>();
        result.put("posts", jdbc.queryForObject("SELECT count(*) FROM telegram_channel_posts", Long.class));
        result.put("current", jdbc.queryForObject("SELECT count(*) FROM telegram_channel_posts WHERE NOT is_archived AND freshness_status IN ('ACTIVE','UNKNOWN') AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)",Long.class));
        result.put("archived", jdbc.queryForObject("SELECT count(*) FROM telegram_channel_posts WHERE is_archived",Long.class));
        result.put("review", jdbc.queryForObject("SELECT (SELECT count(*) FROM telegram_channel_post_relations WHERE classification_status IN ('REVIEW_REQUIRED','FAILED')) + (SELECT count(*) FROM standalone_relation_candidates WHERE status IN ('REVIEW_REQUIRED','FAILED'))",Long.class));
        result.put("faq", jdbc.queryForObject("SELECT count(*) FROM faq_entries WHERE is_active",Long.class));
        return result;
    }

    public PageView posts(String q,String status,String type,String from,String to,int page,int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1"); var args = new ArrayList<Object>();
        search(where,args,q,"p.text");
        if (status != null && !status.isBlank() && !status.equals("ALL")) {
            switch(status) {
                case "CURRENT" -> where.append(" AND NOT p.is_archived AND p.freshness_status IN ('ACTIVE','UNKNOWN') AND (p.expires_at IS NULL OR p.expires_at>CURRENT_TIMESTAMP)");
                case "EXPIRED" -> where.append(" AND NOT p.is_archived AND (p.freshness_status='EXPIRED' OR p.expires_at<=CURRENT_TIMESTAMP)");
                case "ARCHIVED" -> where.append(" AND p.is_archived");
                case "UNKNOWN","INVALID","ACTIVE" -> {where.append(" AND p.freshness_status=? AND NOT p.is_archived"); args.add(status);}
                default -> throw new IllegalArgumentException("Неизвестный статус публикаций");
            }
        }
        if(type != null && !type.isBlank() && !type.equals("ALL")) {
            com.careerai.backend.channel.TelegramChannelPostType.valueOf(type);
            where.append(" AND m.post_type=?"); args.add(type);
        }
        LocalDate start = from == null || from.isBlank() ? null : LocalDate.parse(from);
        LocalDate end = to == null || to.isBlank() ? null : LocalDate.parse(to);
        if (start != null && end != null && start.isAfter(end)) throw new IllegalArgumentException("Начало периода позже окончания");
        if(start != null) {where.append(" AND p.posted_at>=?");args.add(start.atStartOfDay(clock.getZone()).toOffsetDateTime());}
        if(end != null) {where.append(" AND p.posted_at<?");args.add(end.plusDays(1).atStartOfDay(clock.getZone()).toOffsetDateTime());}
        String tables=" FROM telegram_channel_posts p LEFT JOIN telegram_channel_post_metadata m ON m.post_id=p.id";
        return page("SELECT p.id,p.telegram_chat_id,p.telegram_message_id,p.channel_title,p.channel_username,left(p.text,600) AS text,p.posted_at,p.expires_at,p.freshness_status,p.is_archived,p.revision,m.title,m.post_type,m.extraction_status",tables,where.toString()," ORDER BY p.posted_at DESC NULLS LAST,p.id DESC",args,page,size);
    }

    public Map<String,Object> post(long id) {
        var rows=jdbc.queryForList("SELECT id,telegram_chat_id,telegram_message_id,channel_title,channel_username,text,posted_at,edited_at,freshness_status,expires_at,freshness_reason,is_archived,archive_reason,revision FROM telegram_channel_posts WHERE id=?",id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Публикация не найдена");
        var result=rows.getFirst();
        var metadata=jdbc.queryForList("SELECT id,post_type,title,company,technologies,level_text,format_text,deadline_text,practice_start_text,practice_end_text,summary,is_relevant_for_practice,extraction_status,extraction_error,revision FROM telegram_channel_post_metadata WHERE post_id=?",id);
        result.put("metadata",metadata.isEmpty()?null:metadata.getFirst());
        result.put("relations",jdbc.queryForList("SELECT id,source_post_id,target_post_id,relation_type,reason,relation_origin,classification_status,classification_confidence,entity_version FROM telegram_channel_post_relations WHERE source_post_id=? OR target_post_id=? ORDER BY id DESC",id,id));
        return result;
    }

    public PageView faqs(String q,int page,int size) {
        var where=new StringBuilder(" WHERE 1=1");var args=new ArrayList<Object>();search(where,args,q,"question || ' ' || full_answer");
        return page("SELECT id,category,slug,question,short_answer,full_answer,keywords,priority,is_active,revision"," FROM faq_entries",where.toString()," ORDER BY priority,id",args,page,size);
    }
    public PageView relations(String status,int page,int size) {
        var args=new ArrayList<Object>();String where=" WHERE 1=1";
        if(status!=null&&!status.isBlank()&&!status.equals("ALL")) {
            com.careerai.backend.channel.TelegramChannelPostRelationClassificationStatus.valueOf(status);
            where+=" AND r.classification_status=?";args.add(status);
        }
        return page("SELECT r.id,r.source_post_id,r.target_post_id,r.relation_type,r.reason,r.relation_origin,r.classification_status,r.classification_confidence,r.proposed_relation_type,r.proposed_reason,r.classification_error,r.classification_raw_response,r.entity_version,left(s.text,700) AS source_text,left(t.text,700) AS target_text"," FROM telegram_channel_post_relations r JOIN telegram_channel_posts s ON s.id=r.source_post_id JOIN telegram_channel_posts t ON t.id=r.target_post_id",where," ORDER BY r.updated_at DESC,r.id DESC",args,page,size);
    }
    public PageView audit(int page,int size) {return page("SELECT *"," FROM admin_audit_events",""," ORDER BY id DESC",new ArrayList<>(),page,size);}
    private PageView page(String select,String tables,String where,String order,List<Object> args,int page,int size) {
        if(page<0||page>100000||size<1||size>100)throw new IllegalArgumentException("Некорректная страница");
        long total=Objects.requireNonNull(jdbc.queryForObject("SELECT count(*)"+tables+where,Long.class,args.toArray()));
        var paged=new ArrayList<>(args);paged.add(size);paged.add((long)page*size);
        return new PageView(jdbc.queryForList(select+tables+where+order+" LIMIT ? OFFSET ?",paged.toArray()),total,page,size);
    }
    private static void search(StringBuilder where,List<Object> args,String q,String column) {
        if(q==null||q.isBlank())return;
        if(q.length()>300)throw new IllegalArgumentException("Поисковый запрос слишком длинный");
        where.append(" AND ").append(column).append(" ILIKE ? ESCAPE '~'");
        args.add("%"+q.replace("~","~~").replace("%","~%").replace("_","~_")+"%");
    }
}
