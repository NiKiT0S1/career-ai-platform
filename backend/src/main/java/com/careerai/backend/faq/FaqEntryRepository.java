package com.careerai.backend.faq;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * Репозиторий для FAQ-записей ЦКиТ.
 */

public interface FaqEntryRepository extends JpaRepository<FaqEntry, Long> {

    /**
     * Возвращает все активные FAQ-записи в порядке отображения.
     */
    @Query("""
            SELECT entry
            FROM FaqEntry entry
            WHERE entry.active = true
            ORDER BY entry.priority ASC, entry.id ASC
            """)
    List<FaqEntry> findAllActiveOrderByPriority();
}
