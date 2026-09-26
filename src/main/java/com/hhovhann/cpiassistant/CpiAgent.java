package com.hhovhann.cpiassistant;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * The agent. There is no implementation class: LangChain4j's AiServices
 * builds one at startup (see LangChain4jConfig.cpiAgent) and runs the loop —
 * send the question with the tool descriptions, run any tool the model asks
 * for, send the result back, repeat until the model answers in plain text.
 * <p>
 * No chat memory: every question starts fresh, like /ask.
 */
public interface CpiAgent {

    @SystemMessage("""
            You are an assistant for SAP Cloud Integration (CPI).
            Answer questions about CPI using the searchCpiDocs tool. Do not answer \
            from your own knowledge: search first, then answer only from the passages \
            the tool returns.
            Cite the file name of every passage you use, in square brackets, like \
            [02-jdbc-adapter.txt].
            If the passages do not contain the answer, say "I don't know".
            If the question is not about CPI, say so and do not search.""")
    Result<String> answer(@UserMessage String question);
}
