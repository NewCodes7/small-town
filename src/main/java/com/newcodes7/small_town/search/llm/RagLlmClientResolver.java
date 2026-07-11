package com.newcodes7.small_town.search.llm;

import com.newcodes7.small_town.search.config.RagModelProperties.Provider;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Provider → RagLlmClient 매핑 */
@Component
public class RagLlmClientResolver {

    private final Map<Provider, RagLlmClient> clients;

    public RagLlmClientResolver(List<RagLlmClient> clientList) {
        this.clients = new EnumMap<>(Provider.class);
        for (RagLlmClient client : clientList) {
            this.clients.put(client.provider(), client);
        }
    }

    public RagLlmClient resolve(Provider provider) {
        RagLlmClient client = clients.get(provider);
        if (client == null) {
            throw new IllegalStateException("등록되지 않은 LLM 프로바이더: " + provider);
        }
        return client;
    }
}
