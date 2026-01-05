package org.example.inventoryservice.domain;

import jakarta.persistence.Entity;
import lombok.Getter;
import lombok.Setter;


public enum OperationStatus {
    PROCESSING,
    SUCCESS,
    FAILED,
}