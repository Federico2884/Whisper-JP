package com.federico.whisperjp.mt;

import androidx.annotation.NonNull;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.function.Consumer;

/**
 * Japanese → English on-device translation via Google ML Kit. The language pack
 * (~30 MB) downloads once on first use, then runs fully offline. All calls are
 * asynchronous (ML Kit uses its own worker threads).
 */
public final class MtTranslator {

    public interface Callback {
        void onResult(@NonNull String english);
    }

    private final Translator translator;
    private volatile boolean ready;

    public MtTranslator() {
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.JAPANESE)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build();
        translator = Translation.getClient(options);
    }

    /** Downloads the JA→EN model if needed. {@code onReady} gets true on success. */
    public void prepare(@NonNull Consumer<Boolean> onReady) {
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                .addOnSuccessListener(v -> {
                    ready = true;
                    onReady.accept(true);
                })
                .addOnFailureListener(e -> onReady.accept(false));
    }

    public boolean isReady() {
        return ready;
    }

    public void translate(@NonNull String japanese, @NonNull Callback cb) {
        translator.translate(japanese)
                .addOnSuccessListener(cb::onResult)
                .addOnFailureListener(e -> {
                    // leave the previous subtitle on screen
                });
    }

    public void close() {
        translator.close();
    }
}
