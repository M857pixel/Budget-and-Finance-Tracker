package com.pocketledger.repository;

import com.pocketledger.model.TestRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestRecordRepository extends JpaRepository<TestRecord, Long> {
}