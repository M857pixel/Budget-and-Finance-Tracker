package com.pocketledger.model;

import jakarta.persistence.*;

@Entity
@Table(name = "test_records")
public class TestRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String message;

    public TestRecord() {
    }

    public TestRecord(String message) {
        this.message = message;
    }

    public Long getId() {
        return id;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}