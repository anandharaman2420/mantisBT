package com.mantis.repo;

import com.mantis.domain.ApiToken;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ApiTokenRepository extends JpaRepository<ApiToken, Long> {
    Optional<ApiToken> findByTokenHash(String tokenHash);
    List<ApiToken> findByUserIdOrderByDateCreatedDesc(Long userId);
    void deleteByUserId(Long userId);
}
