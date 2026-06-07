package soloMapling.server.EventMessageSystem;

import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static soloMapling.DebugUtilities.debugprint;

/*
Shared historical database for all events
Central ring buffer that keeps last N events for any bot to query later
Purpose: Non-subscribed bots might want to check recent events
Pull based
 */

public class EventStore {
    private final GameEvent[] buffer;
    private final int capacity;
    private int head = 0;
    private int size = 0;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public EventStore(int capacity) {
        this.capacity = capacity;
        this.buffer = new GameEvent[capacity];
    }

    public void add(GameEvent event) {
        debugprint("Adding event to EventStore: ", event.getPlayerName(), event.getId(), event.getType());
        lock.writeLock().lock();
        try {
            buffer[head] = event;
            head = (head + 1) % capacity;
            if (size < capacity) {
                size++;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<GameEvent> getEventsByType(EventType type) {
        return getEventsByType(type, Long.MAX_VALUE);
    }

    public List<GameEvent> getEventsByType(EventType type, long sinceTimestamp) {
        lock.readLock().lock();
        try {
            List<GameEvent> results = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                GameEvent event = buffer[i];
                if (event != null && event.getType() == type &&
                        event.getTimestamp() >= sinceTimestamp) {
                    results.add(event);
                }
            }
            return results;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<GameEvent> getEventsByLocation(int world, int channel, Integer mapId) {
        lock.readLock().lock();
        try {
            List<GameEvent> results = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                GameEvent event = buffer[i];
                if (event != null &&
                        event.getWorld() == world &&
                        event.getChannel() == channel &&
                        (mapId == null || event.getMap().getId() == mapId)) {
                    results.add(event);
                }
            }
            return results;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<GameEvent> getRecentEvents(int count) {
        lock.readLock().lock();
        try {
            List<GameEvent> results = new ArrayList<>();
            int start = (head - Math.min(count, size) + capacity) % capacity;
            for (int i = 0; i < Math.min(count, size); i++) {
                GameEvent event = buffer[(start + i) % capacity];
                if (event != null) {
                    results.add(event);
                }
            }
            return results;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<GameEvent> getEventsInTimeRange(long startTime, long endTime) {
        lock.readLock().lock();
        try {
            List<GameEvent> results = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                GameEvent event = buffer[i];
                if (event != null &&
                        event.getTimestamp() >= startTime &&
                        event.getTimestamp() <= endTime) {
                    results.add(event);
                }
            }
            return results;
        } finally {
            lock.readLock().unlock();
        }
    }
}
