package com.ran.cjb_agent.repository;

import com.ran.cjb_agent.model.entity.ModelConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ModelConfigRepository extends JpaRepository<ModelConfigEntity, Integer> {

    Optional<ModelConfigEntity> findTopByOrderByUpdatedAtDesc();
}
