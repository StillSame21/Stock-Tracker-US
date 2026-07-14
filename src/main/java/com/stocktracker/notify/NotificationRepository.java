package com.stocktracker.notify;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    // SELECT ... FOR UPDATE SKIP LOCKED, written directly into the native query — Step 6:
    // multiple instances polling the outbox must not double-send the same row. (A JPA
    // @Lock annotation on a native query doesn't reliably apply here; the lock clause
    // has to be explicit SQL.)
    @Query(value = "select * from notifications where status = 'PENDING' order by created_at limit :limit for update skip locked",
            nativeQuery = true)
    List<Notification> lockNextPending(@Param("limit") int limit);
}
