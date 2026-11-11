package com.pocketledger.repository;
import com.pocketledger.model.AccountToken;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AccountTokenRepository extends JpaRepository<AccountToken, String> {
    @org.springframework.data.jpa.repository.Query("select t.userId from AccountToken t where t.hash = :hash")
    java.util.Optional<Long> owner(@org.springframework.data.repository.query.Param("hash") String hash);
    void deleteByUserIdAndPurpose(Long userId, String purpose);
}
