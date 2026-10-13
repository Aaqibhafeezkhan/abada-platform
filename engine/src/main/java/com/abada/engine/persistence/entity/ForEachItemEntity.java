package com.abada.engine.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One item of a for-each visit (E21): its state, its child, and what the child returned. */
@Entity
@Table(name = "for_each_items")
public class ForEachItemEntity {
    public enum State { PENDING, RUNNING, DONE, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(name = "parent_instance_id") private String parentInstanceId;
    @Column(name = "parent_token_id") private String parentTokenId;
    @Column(name = "activity_id") private String activityId;
    @Column(name = "item_index") private int itemIndex;
    @Enumerated(EnumType.STRING) private State state = State.PENDING;
    @Column(name = "item_json", columnDefinition = "TEXT") private String itemJson;
    @Column(name = "child_instance_id") private String childInstanceId;
    @Column(name = "outputs_json", columnDefinition = "TEXT") private String outputsJson;
    @Column(name = "error_code") private String errorCode;
    @Column(name = "created_at") private Instant createdAt = Instant.now();
    @Column(name = "finished_at") private Instant finishedAt;

    public long getId() { return id; }
    public String getParentInstanceId() { return parentInstanceId; }
    public void setParentInstanceId(String value) { parentInstanceId = value; }
    public String getParentTokenId() { return parentTokenId; }
    public void setParentTokenId(String value) { parentTokenId = value; }
    public String getActivityId() { return activityId; }
    public void setActivityId(String value) { activityId = value; }
    public int getItemIndex() { return itemIndex; }
    public void setItemIndex(int value) { itemIndex = value; }
    public State getState() { return state; }
    public void setState(State value) { state = value; }
    public String getItemJson() { return itemJson; }
    public void setItemJson(String value) { itemJson = value; }
    public String getChildInstanceId() { return childInstanceId; }
    public void setChildInstanceId(String value) { childInstanceId = value; }
    public String getOutputsJson() { return outputsJson; }
    public void setOutputsJson(String value) { outputsJson = value; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String value) { errorCode = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant value) { finishedAt = value; }
}
