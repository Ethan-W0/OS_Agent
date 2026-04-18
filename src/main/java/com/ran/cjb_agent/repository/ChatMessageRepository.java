package com.ran.cjb_agent.repository;

import com.ran.cjb_agent.model.entity.ChatMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    List<ChatMessageEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    Optional<ChatMessageEntity> findFirstBySessionIdAndTypeOrderByCreatedAtAsc(String sessionId, String type);

    void deleteBySessionId(String sessionId);

    long countBySessionId(String sessionId);
}
