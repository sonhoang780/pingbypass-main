package eu.client.events;

import lombok.Data;

@Data
public class Event {
    private boolean cancelled;

    public void cancel() {
        this.cancelled = true;
    }
}
