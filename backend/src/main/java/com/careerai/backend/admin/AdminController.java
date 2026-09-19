package com.careerai.backend.admin;

import com.careerai.backend.channel.*;
import com.careerai.backend.semantic.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminReadService read;
    private final AdminMutationService mutations;
    private final AdminJobService jobs;
    private final SemanticEmbeddingLifecycleService lifecycle;
    private final StandaloneRelationService standalone;
    public AdminController(AdminReadService read,AdminMutationService mutations,AdminJobService jobs,
                           SemanticEmbeddingLifecycleService lifecycle,StandaloneRelationService standalone) {
        this.read=read;this.mutations=mutations;this.jobs=jobs;this.lifecycle=lifecycle;this.standalone=standalone;
    }
    @GetMapping("/me") public AdminIdentity me(@RequestAttribute(AdminAuthenticationFilter.IDENTITY) AdminIdentity who) {return who;}
    @GetMapping("/overview") public Object overview() {return read.overview();}
    @GetMapping("/posts") public Object posts(@RequestParam(defaultValue="")String q,@RequestParam(defaultValue="ALL")String status,
            @RequestParam(defaultValue="ALL")String type,@RequestParam(defaultValue="")String from,@RequestParam(defaultValue="")String to,
            @RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size) {return read.posts(q,status,type,from,to,page,size);}
    @GetMapping("/posts/{id}") public Object post(@PathVariable long id) {return read.post(id);}
    @PostMapping("/posts/{id}/{action:archive|restore|freshness|extract}") public Object postAction(@PathVariable long id,@PathVariable String action,
            @Valid @RequestBody AdminMutationService.PostAction input,@RequestAttribute(AdminAuthenticationFilter.IDENTITY)AdminIdentity who) {
        mutations.postAction(who.userId(),id,action,input);return read.post(id);
    }
    @PutMapping("/posts/{id}/metadata") public Object metadata(@PathVariable long id,@Valid @RequestBody AdminMutationService.MetadataInput input,
            @RequestAttribute(AdminAuthenticationFilter.IDENTITY)AdminIdentity who) {mutations.metadata(who.userId(),id,input);return read.post(id);}
    @PostMapping("/posts/{id}/reindex") @ResponseStatus(HttpStatus.ACCEPTED)
    public Object reindex(@PathVariable long id,@RequestAttribute(AdminAuthenticationFilter.IDENTITY)AdminIdentity who) {
        read.post(id);return jobs.submit(who.userId(),"post-reindex",id,()->requireOutcome(lifecycle.indexChannelPost(id,true)));
    }
    @PostMapping("/posts/{id}/discover") @ResponseStatus(HttpStatus.ACCEPTED)
    public Object discover(@PathVariable long id,@RequestAttribute(AdminAuthenticationFilter.IDENTITY)AdminIdentity who) {
        read.post(id);return jobs.submit(who.userId(),"post-discover",id,()->standalone.discover(id));
    }
    @GetMapping("/faqs") public Object faqs(@RequestParam(defaultValue="")String q,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size) {return read.faqs(q,page,size);}
    @PostMapping("/faqs") @ResponseStatus(HttpStatus.CREATED)
    public Object createFaq(@Valid @RequestBody AdminMutationService.FaqInput input,@RequestAttribute(AdminAuthenticationFilter.IDENTITY)AdminIdentity who) {return Map.of("id",mutations.saveFaq(who.userId(),null,input));}
    @PutMapping("/faqs/{id}") public Object updateFaq(@PathVariable long id,@Valid @RequestBody AdminMutationService.FaqInput input,
            @RequestAttribute(AdminAuthenticationFilter.IDENTITY)AdminIdentity who) {return Map.of("id",mutations.saveFaq(who.userId(),id,input));}
    @GetMapping("/relations") public Object relations(@RequestParam(defaultValue="ALL")String status,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size) {return read.relations(status,page,size);}
    @PostMapping("/relations") public Object link(@Valid @RequestBody AdminMutationService.LinkInput input,@RequestAttribute(AdminAuthenticationFilter.IDENTITY)AdminIdentity who) {mutations.link(who.userId(),input);return Map.of("ok",true);}
    @PostMapping("/relations/{id}/confirm") public Object confirm(@PathVariable long id,@Valid @RequestBody AdminMutationService.RelationInput input,
            @RequestAttribute(AdminAuthenticationFilter.IDENTITY)AdminIdentity who) {mutations.confirmRelation(who.userId(),id,input);return Map.of("ok",true);}
    @PostMapping("/relations/{id}/remove") public Object remove(@PathVariable long id,@Valid @RequestBody AdminMutationService.PostAction input,
            @RequestAttribute(AdminAuthenticationFilter.IDENTITY)AdminIdentity who) {mutations.removeRelation(who.userId(),id,input);return Map.of("ok",true);}
    @PostMapping("/relations/{id}/retry") public Object retry(@PathVariable long id,@Valid @RequestBody AdminMutationService.PostAction input,
            @RequestAttribute(AdminAuthenticationFilter.IDENTITY)AdminIdentity who) {mutations.retryRelation(who.userId(),id,input);return Map.of("ok",true);}
    @GetMapping("/candidates") public Object candidates(@RequestParam(required=false)StandaloneRelationCandidateStatus status,
            @RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size) {
        if(page<0||page>100000||size<1||size>100)throw new IllegalArgumentException("Некорректная страница");
        var result=standalone.list(status,page,size);
        return Map.of("items",result.getContent(),"total",result.getTotalElements(),"page",page,"size",size);
    }
    public record CandidateDecision(TelegramChannelPostRelationType type,@NotBlank @Size(max=2000)String reason) {}
    @PostMapping("/candidates/{id}/approve") public Object approveCandidate(@PathVariable long id,@Valid @RequestBody CandidateDecision input,
            @RequestAttribute(AdminAuthenticationFilter.IDENTITY)AdminIdentity who) {return standalone.approve(id,input.type(),input.reason(),Long.toString(who.userId()));}
    @PostMapping("/candidates/{id}/reject") public Object rejectCandidate(@PathVariable long id,@Valid @RequestBody CandidateDecision input,
            @RequestAttribute(AdminAuthenticationFilter.IDENTITY)AdminIdentity who) {return standalone.reject(id,input.reason(),Long.toString(who.userId()));}
    @PostMapping("/candidates/{id}/retry") public Object retryCandidate(@PathVariable long id,@RequestAttribute(AdminAuthenticationFilter.IDENTITY)AdminIdentity who) {return standalone.retry(id,Long.toString(who.userId()));}
    @GetMapping("/candidates/{id}/audit") public Object candidateAudit(@PathVariable long id) {return standalone.audit(id);}
    @GetMapping("/audit") public Object audit(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="30")int size) {return read.audit(page,size);}
    @GetMapping("/jobs") public Object jobs() {return jobs.list();}
    @PostMapping("/jobs/embeddings") @ResponseStatus(HttpStatus.ACCEPTED)
    public Object reconcile(@RequestAttribute(AdminAuthenticationFilter.IDENTITY)AdminIdentity who) {
        return jobs.submit(who.userId(),"embedding-batch",null,()->{var result=lifecycle.reconcileBatch();if(!result.enabled()||result.failed()>0)throw new IllegalStateException("Embedding batch incomplete");});
    }
    @PostMapping("/jobs/candidates") @ResponseStatus(HttpStatus.ACCEPTED)
    public Object processCandidates(@RequestAttribute(AdminAuthenticationFilter.IDENTITY)AdminIdentity who) {return jobs.submit(who.userId(),"candidate-batch",null,()->standalone.processPending(5));}
    private static void requireOutcome(SemanticIndexingOutcome outcome) {
        if(outcome!=SemanticIndexingOutcome.INDEXED&&outcome!=SemanticIndexingOutcome.UNCHANGED&&outcome!=SemanticIndexingOutcome.DELETED)
            throw new IllegalStateException("Indexing did not complete: "+outcome);
    }
}
