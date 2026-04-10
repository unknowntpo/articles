package io.example;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ClickEvent {

    @JsonProperty("ad_id")
    public String adId;

    @JsonProperty("count")
    public int count;

    public ClickEvent() {}

    public ClickEvent(String adId, int count) {
        this.adId = adId;
        this.count = count;
    }

    @Override
    public String toString() {
        return "ClickEvent{adId='" + adId + "', count=" + count + "}";
    }
}
