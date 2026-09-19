package com.careerai.backend.channel;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/** Explicit timeline queries have their own eligibility; normal search is unchanged. */
public interface ChannelPostTimelineRepository extends JpaRepository<TelegramChannelPost, Long> {
    @Query("""
        select post from TelegramChannelPostMetadata metadata join metadata.post post
        where metadata.extractionStatus = 'SUCCESS'
          and (metadata.postType in :types or (:practice = true and metadata.relevantForPractice = true))
          and post.archived = false
          and post.freshnessStatus in ('ACTIVE', 'UNKNOWN', 'EXPIRED')
          and post.text is not null and length(trim(post.text)) > 0
          and coalesce(post.postedAt, post.createdAt) >= :fromInclusive
          and coalesce(post.postedAt, post.createdAt) < :toExclusive
          and (
            :freshness = 'ALL'
            or (:freshness = 'CURRENT' and post.freshnessStatus in ('ACTIVE', 'UNKNOWN')
                and (post.expiresAt is null or post.expiresAt > :now))
            or (:freshness = 'EXPIRED' and (post.freshnessStatus = 'EXPIRED' or post.expiresAt <= :now))
          )
        order by coalesce(post.postedAt, post.createdAt) desc, post.id desc
        """)
    List<TelegramChannelPost> findInWindow(@Param("types") Collection<TelegramChannelPostType> types,
                                         @Param("practice") boolean practice,
                                         @Param("freshness") String freshness,
                                         @Param("fromInclusive") OffsetDateTime fromInclusive,
                                         @Param("toExclusive") OffsetDateTime toExclusive,
                                         @Param("now") OffsetDateTime now, Pageable pageable);
}
