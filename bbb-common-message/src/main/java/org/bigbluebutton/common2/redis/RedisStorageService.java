/**
* BigBlueButton open source conferencing system - http://www.bigbluebutton.org/
* 
* Copyright (c) 2018 BigBlueButton Inc. and by respective authors (see below).
*
* This program is free software; you can redistribute it and/or modify it under the
* terms of the GNU Lesser General Public License as published by the Free Software
* Foundation; either version 3.0 of the License, or (at your option) any later
* version.
* 
* BigBlueButton is distributed in the hope that it will be useful, but WITHOUT ANY
* WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
* PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License along
* with BigBlueButton; if not, see <http://www.gnu.org/licenses/>.
*
*/

package org.bigbluebutton.common2.redis;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

public class RedisStorageService extends RedisAwareCommunicator {

    private static Logger log = LoggerFactory.getLogger(RedisStorageService.class);

    StatefulRedisConnection<String, String> connection;

    public void start() {
        log.info("Starting RedisStorageService with client name: {}", clientName);
        RedisURI redisUri = RedisURI.Builder.redis(this.host, this.port).withClientName(this.clientName)
                .withPassword(this.password).build();

        redisClient = RedisClient.create(redisUri);
        redisClient.setOptions(ClientOptions.builder().autoReconnect(true).build());
        eventBus = redisClient.getResources().eventBus();
        eventBusSubscription = eventBus.get().subscribe(e -> connectionStatusHandler(e, log));

        connection = redisClient.connect();
    }

    public void stop() {
        eventBusSubscription.dispose();
        connection.close();
        redisClient.shutdown();
        log.info("RedisStorageService Stopped");
    }

    public void recordMeetingInfo(String meetingId, Map<String, String> info) {
        log.debug("Storing meeting {} metadata {}", meetingId, info);
        recordMeeting(Keys.MEETING_INFO + meetingId, info);
    }

    public void recordBreakoutInfo(String meetingId, Map<String, String> breakoutInfo) {
        log.debug("Saving breakout metadata in {}", meetingId);
        recordMeeting(Keys.BREAKOUT_MEETING + meetingId, breakoutInfo);
    }

    public void addBreakoutRoom(String parentId, String breakoutId) {
        log.debug("Saving breakout room for meeting {}", parentId);
        RedisCommands<String, String> commands = connection.sync();
        commands.sadd(Keys.BREAKOUT_ROOMS + parentId, breakoutId);
    }

    public void record(String meetingId, Map<String, String> event) {
        log.debug("Recording meeting event {} inside a transaction", meetingId);
        RedisCommands<String, String> commands = connection.sync();
        Long msgid = commands.incr("global:nextRecordedMsgId");
        commands.hmset("recording:" + meetingId + ":" + msgid, event);
        commands.rpush("meeting:" + meetingId + ":" + "recordings", Long.toString(msgid));
    }

    // @fixme: not used anywhere
    public void removeMeeting(String meetingId) {
        log.debug("Removing meeting meeting {} inside a transaction", meetingId);
        RedisCommands<String, String> commands = connection.sync();
        commands.del(Keys.MEETING + meetingId);
        commands.srem(Keys.MEETINGS + meetingId);
    }

    public void recordAndExpire(String meetingId, Map<String, String> event) {
        log.debug("Recording meeting event {} inside a transaction", meetingId);
        RedisCommands<String, String> commands = connection.sync();

        Long msgid = commands.incr("global:nextRecordedMsgId");
        String key = "recording:" + meetingId + ":" + msgid;
        commands.hmset(key, event);
        /**
         * We set the key to expire after 14 days as we are still recording the
         * event into redis even if the meeting is not recorded. (ralam sept 23,
         * 2015)
         */
        commands.expire(key, expireKey);
        key = "meeting:" + meetingId + ":recordings";
        commands.rpush(key, Long.toString(msgid));
        commands.expire(key, expireKey);
    }

    private String recordMeeting(String key, Map<String, String> info) {
        log.debug("Storing metadata {}", info);
        String result = "";
        RedisCommands<String, String> commands = connection.sync();
        result = commands.hmset(key, info);
        return result;
    }
}