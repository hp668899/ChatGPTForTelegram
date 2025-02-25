package code.util;

import code.config.RequestProxyConfig;
import code.util.gpt.GPTCallback;
import code.util.gpt.parameter.GPTChatParameter;
import code.util.gpt.parameter.GPTCreateImageParameter;
import code.util.gpt.parameter.GPTTranscriptionsParameter;
import code.util.gpt.response.*;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.entity.mime.HttpMultipartMode;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.fluent.Content;
import org.apache.hc.client5.http.fluent.Request;
import org.apache.hc.client5.http.fluent.Response;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Timeout;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Slf4j
public class GPTUtil {

    public static GPTCreateImageResponse createImage(RequestProxyConfig requestProxyConfig, String token, GPTCreateImageParameter parameter) {
        GPTCreateImageResponse gptCreateImageResponse = new GPTCreateImageResponse();
        gptCreateImageResponse.setOk(false);
        try {
            Request request = Request
                    .post("https://api.openai.com/v1/images/generations")
                    .setHeader("Authorization", "Bearer " + token)
                    .bodyString(JSON.toJSONString(parameter), ContentType.APPLICATION_JSON)
                    .connectTimeout(Timeout.ofSeconds(30))
                    .responseTimeout(Timeout.ofSeconds(60));
            requestProxyConfig.viaProxy(request);
            Response response = request.execute();

            Content content = response.returnContent();
            String s = content.asString();
            if (StringUtils.isNotBlank(s)) {
                GPTCreateImageResponse gptCreateImageResponse2 = JSON.parseObject(s, GPTCreateImageResponse.class, JSONReader.Feature.SupportSmartMatch);
                if (null != gptCreateImageResponse2 && null != gptCreateImageResponse2.getData() && !gptCreateImageResponse2.getData().isEmpty()) {
                    gptCreateImageResponse2.setOk(true);
                    return gptCreateImageResponse2;
                }
            }

        } catch (Exception e) {
            log.error(ExceptionUtil.getStackTraceWithCustomInfoToStr(e));
        }
        return gptCreateImageResponse;
    }

    public static GPTTranscriptionsResponse transcriptions(RequestProxyConfig requestProxyConfig, String token, GPTTranscriptionsParameter parameter) {
        GPTTranscriptionsResponse gptTranscriptionsResponse = new GPTTranscriptionsResponse();
        gptTranscriptionsResponse.setOk(false);
        try {
            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            builder.setMode(HttpMultipartMode.LEGACY);
            builder.addBinaryBody("file", parameter.getFile(), ContentType.DEFAULT_BINARY, parameter.getFile().getName());
            builder.addTextBody("model", parameter.getModel(), ContentType.DEFAULT_BINARY);
            HttpEntity entity = builder.build();

            Request request = Request
                    .post("https://api.openai.com/v1/audio/transcriptions")
                    .body(entity)
                    .setHeader("Authorization", "Bearer " + token)
                    .connectTimeout(Timeout.ofSeconds(30))
                    .responseTimeout(Timeout.ofSeconds(60));
            requestProxyConfig.viaProxy(request);
            Response response = request.execute();

            Content content = response.returnContent();
            String s = content.asString(StandardCharsets.UTF_8);
            if (StringUtils.isNotBlank(s)) {
                GPTTranscriptionsResponse gptTranscriptionsResponse2 = JSON.parseObject(s, GPTTranscriptionsResponse.class, JSONReader.Feature.SupportSmartMatch);
                if (null != gptTranscriptionsResponse2 && StringUtils.isNotBlank(gptTranscriptionsResponse2.getText())) {
                    gptTranscriptionsResponse2.setOk(true);
                    return gptTranscriptionsResponse2;
                }
            }

        } catch (Exception e) {
            log.error(ExceptionUtil.getStackTraceWithCustomInfoToStr(e));
        }
        return gptTranscriptionsResponse;
    }

    public static GPTChatResponse chat(RequestProxyConfig requestProxyConfig, String token, GPTChatParameter parameter, GPTCallback callback) {
        GPTChatResponse chatResponse = new GPTChatResponse();
        chatResponse.setOk(false);

        try {
            Request request = Request
                    .post("https://api.openai.com/v1/chat/completions")
                    .setHeader("Authorization", "Bearer " + token)
                    .setHeader("accept", "text/event-stream")
                    .bodyString(JSON.toJSONString(parameter), org.apache.hc.core5.http.ContentType.APPLICATION_JSON)
                    .connectTimeout(Timeout.ofSeconds(30))
                    .responseTimeout(Timeout.ofSeconds(30))
                    ;
            requestProxyConfig.viaProxy(request);
            Response response = request.execute();

            StringBuilder builder = new StringBuilder();
            StringBuilder responseBuilder = new StringBuilder();
            int code = response.handleResponse((classicHttpResponse) -> {
                InputStream inputStream = classicHttpResponse.getEntity().getContent();

                try (BufferedInputStream in = IOUtils.buffer(inputStream)) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        String line = null;
                        while((line = reader.readLine()) != null) {
                            responseBuilder.append(line);
                            String s = StringUtils.substringAfter(line, "data: ");
                            if (StringUtils.isNotEmpty(s)) {
                                if ("[DONE]".equals(s)) {
                                    GPTCallbackChatContent content = new GPTCallbackChatContent();
                                    content.setDone(true);
                                    callback.call(content);
                                } else {
                                    GPTCallbackChatContent content = JSON.parseObject(s, GPTCallbackChatContent.class, JSONReader.Feature.SupportSmartMatch);
                                    GPTChatContentChoicesDelta delta = content.getChoices().get(0).getDelta();
                                    if (StringUtils.isNotEmpty(delta.getContent())) {
                                        builder.append(content.getChoices().get(0).getDelta().getContent());
                                        content.setDone(false);
                                        content.setContent(builder.toString());
                                        callback.call(content);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error(ExceptionUtil.getStackTraceWithCustomInfoToStr(e));
                }

                return classicHttpResponse.getCode();
            });

            chatResponse.setStatusCode(code);

            String s = builder.toString();
            chatResponse.setOk(StringUtils.isNotEmpty(s));
            chatResponse.setContent(s);
            chatResponse.setResponse(responseBuilder.toString());
        } catch (Exception e) {
            log.error(ExceptionUtil.getStackTraceWithCustomInfoToStr(e));
        }

        return chatResponse;
    }

}
