package com.example.adoptions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import jakarta.servlet.http.HttpSession;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
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
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.data.annotation.Id;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 */
@SpringBootApplication
public class AdoptionsApplication {
	public static void main(String[] args) {
		SpringApplication.run(AdoptionsApplication.class, args);
	}
}

@Configuration
class ConversationalConfiguration {

	// Session-scoped ChatMemory (ephemeral, demo). When this is passed to constructors by
	// Spring it is a proxy that will eventually get a session-based instance when needed.
	// This holds state and is passed into a singleton ChatClient to provide user context
	@Bean
	@Scope(value = WebApplicationContext.SCOPE_SESSION, proxyMode = ScopedProxyMode.TARGET_CLASS)
	public ChatMemory chatMemory(ChatMemoryRepository repo) {
		// Simple rolling memory that keeps the last maxMessages
		return MessageWindowChatMemory.builder()
				.chatMemoryRepository(repo)
				.maxMessages(10)
				.build();
	}

	// In-memory repository for the messages (per JVM)
	// A repo needs to be supplied to create a ChatMemory. This is simple in memory one.
	// This could pull from Redis or a DB, or wherever.
	@Bean
	public ChatMemoryRepository chatMemoryRepository() {
		return new InMemoryChatMemoryRepository();
	}

	@Bean
	public MessageChatMemoryAdvisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
		return MessageChatMemoryAdvisor
				.builder(chatMemory)
				.build();
	}

	// RAG Advisor- A singleton, instance used for every request. Contains no user state.
	@Bean
	public QuestionAnswerAdvisor questionAnswerAdvisor(VectorStore vectorStore, DogRepository dogRepository) {
		dogRepository.findAll().forEach(dog -> {
			var document = new Document("id: %s, dog name: %s, description: up for adoptions: %s, ".formatted(
					dog.id(), dog.name(), dog.description())
			);
			vectorStore.add(List.of(document));
		});
		return new QuestionAnswerAdvisor(vectorStore);
	}

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
			MessageChatMemoryAdvisor memoryAdvisor,
			QuestionAnswerAdvisor questionAnswerAdvisor,
			McpSyncClient mcpSyncClient,
			DogAdoptionScheduler localScheduler
	) {

		var system = """
                You are an AI powered assistant to help people adopt a dog from the adoption\s
                agency named Pooch Palace with locations in Atlanta, Antwerp, Seoul, Tokyo, Singapore, Paris,\s
                Mumbai, New Delhi, Barcelona, San Francisco, and London. Information about the dogs available\s
                will be presented below. If there is no information, then return a polite response suggesting we\s
                don't have any dogs available.
                """;

		builder.defaultSystem(system);
		builder.defaultAdvisors(memoryAdvisor, questionAnswerAdvisor);

		boolean useLocalTools = false;
		if (useLocalTools) {
			builder.defaultTools(localScheduler);
		} else {
			builder.defaultToolCallbacks(new SyncMcpToolCallbackProvider(mcpSyncClient));
		}
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

	ConversationalController(ChatClient chatClient) {
		this.chatClient = chatClient;
	}

	@PostMapping("/{user}/inquire")
	String inquire(@PathVariable("user") String user, @RequestParam String question,  HttpSession session) {
		String sessionId = session.getId();
		System.out.println("inquire: " + question + " sessionId: " + sessionId);
		return chatClient
				.prompt()
				.user(question)
				.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
				.call()
				.content();
	}
}