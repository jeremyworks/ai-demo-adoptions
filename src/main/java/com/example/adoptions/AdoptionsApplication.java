package com.example.adoptions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.VectorStoreChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.annotation.Id;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author James Ward
 * @author Josh Long
 */
@SpringBootApplication
public class AdoptionsApplication {
	public static void main(String[] args) {
		SpringApplication.run(AdoptionsApplication.class, args);
	}
}

@Configuration
class ConversationalConfiguration {

	@Bean
	McpSyncClient mcpSyncClient () {
		var mcp = McpClient
				.sync(HttpClientSseClientTransport.builder("http://localhost:8081").build()).build();
		mcp.initialize();
		return mcp;
	}

	@Bean
	ChatClient chatClient(
			ChatClient.Builder builder,
			McpSyncClient mcpSyncClient,
			VectorStore vectorStore,
			DogRepository dogRepository,
			DogAdoptionScheduler localScheduler
	) {

		dogRepository.findAll().forEach(dog -> {
			var document = new Document("id: %s, dog name: %s, description: up for adoptions: %s, ".formatted(
					dog.id(), dog.name(), dog.description())
			);
			vectorStore.add(List.of(document));
		});

		var system = """
                You are an AI powered assistant to help people adopt a dog from the adoption\s
                agency named Pooch Palace with locations in Atlanta, Antwerp, Seoul, Tokyo, Singapore, Paris,\s
                Mumbai, New Delhi, Barcelona, San Francisco, and London. Information about the dogs available\s
                will be presented below. If there is no information, then return a polite response suggesting we\s
                don't have any dogs available.
                """;

		builder.defaultSystem(system);
		boolean useLocalTools = false;
		if (useLocalTools) {
			builder.defaultTools(localScheduler);
		} else {
			builder.defaultToolCallbacks(new SyncMcpToolCallbackProvider(mcpSyncClient));
		}
//		builder.defaultAdvisors(VectorStoreChatMemoryAdvisor.builder(vectorStore).build());
		return builder.build();
	}
}

interface DogRepository extends ListCrudRepository<Dog, Integer> {
}

record Dog(@Id int id, String name, String owner, String description) {
}

@Component
class DogAdoptionScheduler {

	private final ObjectMapper objectMapper;

	DogAdoptionScheduler(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Tool (description = "schedule an appointment to adopt a dog at Pooch Palace dog adoption agency")
	String scheduleDogAdoptionAppointment (@ToolParam (description = "the id of the dog") int dogId,
										   @ToolParam (description = "the name of the dog") String dogName) throws Exception
	{
		System.out.println("confirming appointment for [" + dogId + "] and [" + dogName + "]");
		var instant = Instant.now().plus(3, ChronoUnit.DAYS);
		return objectMapper.writeValueAsString(instant);
	}
}

@Controller
@ResponseBody
class ConversationalController {

	private final ChatClient chatClient;
	private final Map<String, PromptChatMemoryAdvisor> advisorsMap = new ConcurrentHashMap<>();
	private final QuestionAnswerAdvisor questionAnswerAdvisor;
//	private final DogAdoptionScheduler scheduler;
	private final ChatMemory chatMemory;

	ConversationalController(ChatClient chatClient, VectorStore vectorStore, ChatMemory chatMemory) {
		this.chatClient = chatClient;
		this.questionAnswerAdvisor = new QuestionAnswerAdvisor(vectorStore);
		this.chatMemory = chatMemory;
//		this.scheduler = scheduler;
	}

	@PostMapping("/{user}/inquire")
	String inquire(@PathVariable("user") String user, @RequestParam String question) {

		var advisor = this.advisorsMap.computeIfAbsent(user, key ->
				PromptChatMemoryAdvisor.builder(chatMemory).build());

		return chatClient
				.prompt()
				.user(question)
				.advisors(advisor, this.questionAnswerAdvisor)
				.call()
				.content();
	}
}