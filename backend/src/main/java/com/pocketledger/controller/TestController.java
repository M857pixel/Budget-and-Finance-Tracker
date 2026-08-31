package com.pocketledger.controller;

import com.pocketledger.model.TestRecord;
import com.pocketledger.repository.TestRecordRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:5173")
public class TestController {

    private final TestRecordRepository repository;

    public TestController(TestRecordRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/hello")
    public String hello() {
        return "PocketLedger backend is running!";
    }

    @PostMapping("/test-record")
    public TestRecord createRecord(@RequestBody TestRecord record) {
        return repository.save(record);
    }

    @GetMapping("/test-records")
    public List<TestRecord> getRecords() {
        return repository.findAll();
    }
}