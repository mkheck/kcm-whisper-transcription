package com.thehecklers.kcmwhispertranscription;

//import org.apache.hc.client5.http.classic.methods.HttpPost;
//import org.apache.hc.client5.http.entity.mime.FileBody;
//import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
//import org.apache.hc.client5.http.entity.mime.StringBody;
//import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
//import org.apache.hc.client5.http.impl.classic.HttpClients;
//import org.apache.hc.core5.http.ContentType;
//import org.apache.hc.core5.http.HttpEntity;
//import org.apache.hc.core5.http.io.entity.EntityUtils;
//import org.apache.hc.core5.http.message.StatusLine;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
//import org.springframework.http.RequestEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.io.*;
//import java.io.IOException;
//import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

// See docs at https://platform.openai.com/docs/api-reference/audio/createTranscription

// response_format: json (default), text, srt, verbose_json, vtt
//      "text" is used here, as it returns the transcript directly
// language: ISO-639-1 code (optional)
//
// Rather than use multipart form data, add the file as a binary body directly
// Optional "prompt" used to give standard word spellings whisper might miss
//      If there are multiple chunks, the prompt for subsequent chunks should be the
//      transcription of the previous one (244 tokens max)

// file must be mp3, mp4, mpeg, mpga, m4a, wav, or webm
// NOTE: only wav files are supported here (mp3 apparently is proprietary)

// max size is 25MB; otherwise need to break the file into chunks
// See the WavFileSplitter class for that

public class WhisperTranscribe {
    private final static String URL = "https://api.openai.com/v1/audio/transcriptions";
    public final static int MAX_ALLOWED_SIZE = 25 * 1024 * 1024;
    public final static int MAX_CHUNK_SIZE_BYTES = 20 * 1024 * 1024;

    private final static String KEY = System.getenv("OPENAI_API_KEY");

    // Only model available as of Fall 2023 is whisper-1
    private final static String MODEL = "whisper-1";

    public static final String WORD_LIST = String.join(", ",
            List.of("Kousen", "GPT-3", "GPT-4", "DALL-E",
                    "Midjourney", "AssertJ", "Mockito", "JUnit", "Java", "Kotlin", "Groovy", "Scala",
                    "IOException", "RuntimeException", "UncheckedIOException", "UnsupportedAudioFileException",
                    "assertThrows", "assertTrue", "assertEquals", "assertNull", "assertNotNull", "assertThat",
                    "Tales from the jar side", "Spring Boot", "Spring Framework", "Spring Data", "Spring Security"));

    private String transcribeChunk(String prompt, File chunkFile) {
        System.out.printf("Transcribing %s%n", chunkFile.getName());

        ResponseEntity<String> response = null;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.add("Authorization", "Bearer %s".formatted(KEY));

        try (InputStream inputStream = new FileInputStream(chunkFile)) {
            var contentsAsResource = new ByteArrayResource(inputStream.readAllBytes()) {
                @Override
                public String getFilename() {
                    return chunkFile.getName(); // Filename has to be returned in order to be able to post.
                }
            };

            var builder = new MultipartBodyBuilder();

            builder.part("file", contentsAsResource, MediaType.APPLICATION_OCTET_STREAM);
            builder.part("model", MODEL, MediaType.TEXT_PLAIN);
            builder.part("response_format", "text", MediaType.TEXT_PLAIN);
            builder.part("prompt", prompt, MediaType.TEXT_PLAIN);

            //MultiValueMap<String, HttpEntity<?>> body = builder.build();
            HttpEntity<MultiValueMap<String, HttpEntity<?>>> httpEntity = new HttpEntity<>(builder.build(), headers);

            return new RestTemplate()
                    .postForEntity(URL, httpEntity, String.class)
                    .getBody();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String transcribe(String fileName) {
        System.out.println("Transcribing " + fileName);
        File file = new File(fileName);

        // Collect the transcriptions of each chunk
        List<String> transcriptions = new ArrayList<>();

        // First prompt is the word list
        String prompt = WORD_LIST;

        if (file.length() <= MAX_ALLOWED_SIZE) {
            String transcription = transcribeChunk(prompt, file);
            transcriptions = List.of(transcription);
        } else {
            var splitter = new WavFileSplitter();
            List<File> chunks = splitter.splitWavFileIntoChunks(file);
            for (File chunk : chunks) {
                // Subsequent prompts are the previous transcriptions
                String transcription = transcribeChunk(prompt, chunk);
                transcriptions.add(transcription);
                prompt = transcription;

                // After transcribing, no longer need the chunk
                if (!chunk.delete()) {
                    System.out.println("Failed to delete " + chunk.getName());
                }
            }
        }

        // Join the individual transcripts and write to a file
        String transcription = String.join(" ", transcriptions);
        String fileNameWithoutPath = fileName.substring(
                fileName.lastIndexOf("/") + 1);
        FileUtils.writeTextToFile(transcription,
                fileNameWithoutPath.replace(".wav", ".txt"));
        return transcription;
    }
}