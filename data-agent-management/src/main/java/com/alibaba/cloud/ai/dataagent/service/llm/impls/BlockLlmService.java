/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.dataagent.service.llm.impls;

import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.AiModelRegistry;
import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

@Slf4j
@AllArgsConstructor
public class BlockLlmService implements LlmService {

	private final AiModelRegistry registry;

	@Override
	public Flux<ChatResponse> call(String system, String user) {
		return Mono
			.fromCallable(() -> registry.getChatClient().prompt().system(system).user(user).call().chatResponse())
			.retryWhen(getRetrySpec())
			.doOnSubscribe(subscription -> log.debug("开始调用 LLM（阻塞模式），配置重试策略"))
			.doOnError(error -> log.warn("LLM 调用失败（阻塞模式）：{}", error.getMessage()))
			.flux();
	}

	@Override
	public Flux<ChatResponse> callSystem(String system) {
		return Mono.fromCallable(() -> registry.getChatClient().prompt().system(system).call().chatResponse())
			.retryWhen(getRetrySpec())
			.flux();
	}

	@Override
	public Flux<ChatResponse> callUser(String user) {
		return Mono.fromCallable(() -> registry.getChatClient().prompt().user(user).call().chatResponse())
			.retryWhen(getRetrySpec())
			.flux();
	}

	/**
	 * 获取重试策略配置（从系统属性读取）
	 */
	private Retry getRetrySpec() {
		// 从系统属性读取配置，支持动态调整
		int maxRetries = Integer.getInteger("llm.retry.maxAttempts", 5);
		long initialBackoffMillis = Long.getLong("llm.retry.initialBackoffMillis", 1000L);
		long maxBackoffMillis = Long.getLong("llm.retry.maxBackoffMillis", 30000L);

		return Retry.backoff(maxRetries, Duration.ofMillis(initialBackoffMillis))
			.maxBackoff(Duration.ofMillis(maxBackoffMillis))
			// 对所有错误都进行重试
			.onRetryExhaustedThrow((spec, signal) -> {
				log.error("调用 LLM 失败（阻塞模式），已达最大重试次数 {} 次：{}", maxRetries, signal.failure().getMessage());
				return signal.failure();
			});
	}

}
