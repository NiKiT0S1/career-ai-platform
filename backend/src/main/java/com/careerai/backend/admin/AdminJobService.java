package com.careerai.backend.admin;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
public class AdminJobService {
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(20),r->{Thread t=new Thread(r,"admin-maintenance");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final Cache<String,Job> jobs=Caffeine.newBuilder().maximumSize(200).expireAfterWrite(Duration.ofDays(1)).build();
    private final Set<String> running=ConcurrentHashMap.newKeySet();
    private final AdminAuditService audit;
    public AdminJobService(AdminAuditService audit) {this.audit=audit;}
    public record Job(String id,String action,Long entityId,String status,String message,Instant updatedAt) {}
    public Job submit(long actor,String action,Long entityId,Runnable operation) {
        String key=action+":"+entityId;
        if(!running.add(key))throw new ResponseStatusException(HttpStatus.CONFLICT,"Эта операция уже выполняется");
        String id=UUID.randomUUID().toString();Job pending=new Job(id,action,entityId,"QUEUED","В очереди",Instant.now());jobs.put(id,pending);
        try {
            worker.execute(()->{
                jobs.put(id,new Job(id,action,entityId,"RUNNING","Выполняется",Instant.now()));
                try {operation.run();audit.record(actor,"job-completed",action,entityId,id);jobs.put(id,new Job(id,action,entityId,"COMPLETED","Готово",Instant.now()));}
                catch(Exception e){jobs.put(id,new Job(id,action,entityId,"FAILED","Операция не завершена. Проверьте состояние записи и журнал приложения.",Instant.now()));audit.record(actor,"job-failed",action,entityId,id);}
                finally {running.remove(key);}
            });
            audit.record(actor,"job-queued",action,entityId,id);
            return pending;
        } catch(RejectedExecutionException e) {running.remove(key);jobs.invalidate(id);throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Очередь заполнена. Повторите позже.");}
    }
    public List<Job> list() {return jobs.asMap().values().stream().sorted(Comparator.comparing(Job::updatedAt).reversed()).toList();}
    @PreDestroy public void close() {worker.shutdown();}
}
