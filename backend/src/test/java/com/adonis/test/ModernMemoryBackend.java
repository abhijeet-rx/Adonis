package com.adonis.test;

import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import de.bwaldvogel.mongo.bson.Document;
import io.netty.channel.Channel;

/**
 * ModernMemoryBackend extends de.bwaldvogel.mongo.backend.memory.MemoryBackend
 * to report wire version 13 (MongoDB 5.0 wire protocol compatibility),
 * allowing modern MongoDB Java drivers (5.x) to connect without wire version rejection.
 */
public class ModernMemoryBackend extends MemoryBackend {

    @Override
    public Document handleCommand(Channel channel, String database, String command, Document query) {
        Document response = super.handleCommand(channel, database, command, query);
        if (response != null && response.containsKey("maxWireVersion")) {
            response.put("maxWireVersion", 13);
            response.put("minWireVersion", 0);
        }
        return response;
    }
}
