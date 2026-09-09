package net.swofty.redisapi.events;

import net.swofty.redisapi.api.ChannelRegistry;
import net.swofty.redisapi.api.RedisAPI;
import net.swofty.redisapi.api.RedisChannel;
import net.swofty.redisapi.api.RedisExecutors;
import net.swofty.redisapi.api.requests.DataRequest;
import lombok.SneakyThrows;
import net.swofty.redisapi.exceptions.ChannelDefinitionError;
import net.swofty.redisapi.exceptions.InvalidMessageException;
import redis.clients.jedis.JedisPubSub;

import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public class EventRegistry {

      private static final Logger LOGGER = Logger.getLogger(EventRegistry.class.getName());

      public static JedisPubSub pubSub = null;

      /**
       * Entry point for the subscriber thread. Does the filter check and then hands the message to a
       * worker; nothing else may happen here, because every channel in the process shares this one
       * thread and anything that blocks it stalls all Redis traffic.
       */
      public static void dispatch(String channel, String message) {
            if (!matchesFilter(message))
                  return;

            // Correlation ids make ordering irrelevant for data requests, so they run fully
            // parallel. Everything else is striped by channel name to keep per-channel FIFO.
            if (DataRequest.CHANNEL.equals(channel))
                  RedisExecutors.submit(RedisExecutors.INBOUND, "inbound", () -> run(channel, message));
            else
                  RedisExecutors.submit(RedisExecutors.dispatchFor(channel), "dispatch", () -> run(channel, message));
      }

      private static void run(String channel, String message) {
            try {
                  handleAll(channel, message);
            } catch (Throwable error) {
                  LOGGER.log(Level.SEVERE, "Redis handler for channel " + channel + " failed", error);
            }
      }

      /**
       * Equivalent to the {@code message.split(";")[0]} comparison below without allocating an
       * array for every message that arrives.
       */
      private static boolean matchesFilter(String message) {
            if (message == null) {
                  LOGGER.warning("Received a null Redis message, dropping it.");
                  return false;
            }

            int separator = message.indexOf(';');
            if (separator < 0) {
                  LOGGER.warning("Received message is not properly formatted with a filter ID: " + message);
                  return false;
            }

            if (separator == 3 && message.regionMatches(0, "all", 0, 3))
                  return true;

            String filterId = RedisAPI.getInstance().getFilterId();
            return filterId != null && filterId.length() == separator
                    && message.regionMatches(0, filterId, 0, separator);
      }

      @SneakyThrows
      public static void handleAll(String channel, String message) {
            String filterID;
            if (message != null && message.contains(";")) {
                  filterID = message.split(";", 2)[0];
            } else {
                  throw new InvalidMessageException("Received message is not properly formatted with a filter ID: " + message);
            }

            if (filterID.equals("all") || filterID.equals(RedisAPI.getInstance().getFilterId())) {
                  Optional<RedisChannel> optionalChannelBeingCalled = ChannelRegistry.registeredChannels.stream().filter(channel2 -> Objects.equals(channel2.channelName, channel)).findAny();
                  if (optionalChannelBeingCalled.isPresent()) {
                        RedisChannel channelBeingCalled = optionalChannelBeingCalled.get();

                        switch (channelBeingCalled.functionType) {
                              case CLASS:
                                    RedisMessagingReceiveInterface receiveInterface = channelBeingCalled.receiveInterface.getDeclaredConstructor().newInstance();
                                    receiveInterface.onMessage(channel, message);
                                    return;

                              case CONSUMER:
                                    channelBeingCalled.receiveEvent.accept(new RedisMessagingReceiveEvent(channel, message));
                                    return;
                        }
                        throw new ChannelDefinitionError("No receive event or receive interface was set for the channel '" + channel + "'");
                  }
            }
      }

}
