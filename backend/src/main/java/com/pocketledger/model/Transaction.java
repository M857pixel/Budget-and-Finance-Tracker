package com.pocketledger.model;

import jakarta.persistence.*;

@Entity
@Table(name = "transactions")

// represents a database record. fields are subject to change as we decide on database design
//defines what a transaction looks like
public class Transaction {
    //Unique Id for every transaction, will be auto generated
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    //Basic transaction info:
    private double amount;
    private String description;
    private String category;

    //needed for Jakarta persistence when creating Transaction obj
    public Transaction() {
    }

    //Constructor for creating a transaction
    public Transaction(double amount, String description, String category) {
        this.amount = amount;
        this.description = description;
        this.category = category;
    }
    //get / set values
    public Long getId(){
        return id;
    }

    public double getAmount(){
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public String getDescription(){
        return description;
    }

    public void setDescription(String description){
        this.description = description;
    }

    public String getCategory(){
        return category;
    }

    public void setCategory(String category){
        this.category = category;
    }

}
