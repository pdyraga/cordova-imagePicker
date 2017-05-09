package com.synconset;

import android.net.Uri;

import java.io.File;
import java.io.Serializable;

public final class ResizedImage implements Serializable {
    private final String originalImageUri;
    private final String resizedImageUri;
    private final float scaleFactor;

    public ResizedImage(File originalImage, File resizedImage, float scaleFactor) {
        this.originalImageUri = Uri.fromFile(originalImage).toString();
        this.resizedImageUri = Uri.fromFile(resizedImage).toString();
        this.scaleFactor = scaleFactor;
    }

    public String getOriginalImageUri() {
        return this.originalImageUri;
    }

    public String getResizedImageUri() {
        return this.resizedImageUri;
    }

    public float getScaleFactor() {
        return this.scaleFactor;
    }
}