/*
 * Copyright (c) 2012, David Erosa
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without 
 * modification, are permitted provided that the following  conditions are met:
 *
 *   Redistributions of source code must retain the above copyright notice, 
 *      this list of conditions and the following disclaimer.
 *   Redistributions in binary form must reproduce the above copyright notice, 
 *      this list of conditions and the following  disclaimer in the 
 *      documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,  BUT NOT LIMITED TO, THE 
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE 
 * ARE DISCLAIMED. IN NO EVENT  SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE 
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR 
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF 
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR  BUSINESS 
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN 
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDIN G NEGLIGENCE OR OTHERWISE) 
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE 
 * POSSIBILITY OF SUCH  DAMAGE
 *
 * Code modified by Andrew Stephan for Sync OnSet
 *
 */

package com.synconset;

import java.net.URI;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import android.graphics.*;
import com.synconset.FakeR;
import android.app.Activity;
import android.app.ActionBar;
import android.app.AlertDialog;
import android.app.LoaderManager;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.CursorLoader;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AbsListView.OnScrollListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

public class MultiImageChooserActivity extends Activity implements OnItemClickListener,
        LoaderManager.LoaderCallbacks<Cursor> {
    private static final String TAG = "ImagePicker";

    public static final int NOLIMIT = -1;
    public static final String MAX_IMAGES_KEY = "MAX_IMAGES";
    public static final String WIDTH_KEY = "WIDTH";
    public static final String HEIGHT_KEY = "HEIGHT";
    public static final String QUALITY_KEY = "QUALITY";

    private ImageAdapter ia;

    private Cursor imagecursor, actualimagecursor;
    private int image_column_index, image_column_orientation, actual_image_column_index, orientation_column_index;
    private int colWidth;

    private static final int CURSORLOADER_THUMBS = 0;
    private static final int CURSORLOADER_REAL = 1;

    private Map<String, Integer> fileNames = new HashMap<String, Integer>();

    private SparseBooleanArray checkStatus = new SparseBooleanArray();

    private int maxImages;
    private int maxImageCount;
    
    private int desiredWidth;
    private int desiredHeight;
    private int quality;

    private GridView gridView;

    private final ImageFetcher fetcher = new ImageFetcher();

    private int selectedColor = 0xff32b2e1;
    private boolean shouldRequestThumb = true;
    
    private FakeR fakeR;
    
    private ProgressDialog progress;

    private int imagePickerDialogStyleId;

    private class ScaledBitmap {
        public final Bitmap bitmap;
        public final float scaleFactor;

        public ScaledBitmap(Bitmap bitmap, float scaleFactor) {
            this.bitmap = bitmap;
            this.scaleFactor = scaleFactor;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        fakeR = new FakeR(this);
        setContentView(fakeR.getId("layout", "multiselectorgrid"));
        fileNames.clear();

        imagePickerDialogStyleId = getResources().getIdentifier("ImagePickerDialogStyle", "style", getPackageName());

        maxImages = getIntent().getIntExtra(MAX_IMAGES_KEY, NOLIMIT);
        desiredWidth = getIntent().getIntExtra(WIDTH_KEY, 0);
        desiredHeight = getIntent().getIntExtra(HEIGHT_KEY, 0);
        quality = getIntent().getIntExtra(QUALITY_KEY, 0);
        maxImageCount = maxImages;

        Display display = getWindowManager().getDefaultDisplay();
        int width = display.getWidth();
        
        colWidth = width / 4;

        gridView = (GridView) findViewById(fakeR.getId("id", "gridview"));
        gridView.setOnItemClickListener(this);
        gridView.setOnScrollListener(new OnScrollListener() {
            private int lastFirstItem = 0;
            private long timestamp = System.currentTimeMillis();

            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState == SCROLL_STATE_IDLE) {
                    shouldRequestThumb = true;
                    ia.notifyDataSetChanged();
                }
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                float dt = System.currentTimeMillis() - timestamp;
                if (firstVisibleItem != lastFirstItem) {
                    double speed = 1 / dt * 1000;
                    lastFirstItem = firstVisibleItem;
                    timestamp = System.currentTimeMillis();

                    // Limit if we go faster than a page a second
                    shouldRequestThumb = speed < visibleItemCount;
                }
            }
        });

        ia = new ImageAdapter(this);
        gridView.setAdapter(ia);

        LoaderManager.enableDebugLogging(false);
        getLoaderManager().initLoader(CURSORLOADER_THUMBS, null, this);
        getLoaderManager().initLoader(CURSORLOADER_REAL, null, this);
        setupHeader();
        updateAcceptButton();
        progress = new ProgressDialog(this, this.imagePickerDialogStyleId);
        progress.setTitle("Processing Images");
        progress.setMessage("This may take a few moments");
    }
    
    @Override
    public void onItemClick(AdapterView<?> arg0, View view, int position, long id) {
        String name = getImageName(position);
        int rotation = getImageRotation(position);

        if (name == null) {
            return;
        }
        boolean isChecked = !isChecked(position);
        if (maxImages == 0 && isChecked) {
            isChecked = false;
            AlertDialog.Builder builder = new AlertDialog.Builder(this, this.imagePickerDialogStyleId);
            builder.setTitle("Maximum " + maxImageCount + " Photos");
            builder.setMessage("You can only select " + maxImageCount + " photos at a time.");
            builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) { 
                    dialog.cancel();
                }
            });
            AlertDialog alert = builder.create();
            alert.show();
        } else if (isChecked) {
            fileNames.put(name, new Integer(rotation));
            if (maxImageCount == 1) {
                this.selectClicked(null);
            } else {
                maxImages--;
                ImageView imageView = (ImageView)view;
                if (android.os.Build.VERSION.SDK_INT>=16) {
                  imageView.setImageAlpha(128);
                } else {
                  imageView.setAlpha(128);
                }
                view.setBackgroundColor(selectedColor);
            }
        } else {
            fileNames.remove(name);
            maxImages++;
            ImageView imageView = (ImageView)view;
            if (android.os.Build.VERSION.SDK_INT>=16) {
                imageView.setImageAlpha(255);
            } else {
                imageView.setAlpha(255);
            }
            view.setBackgroundColor(Color.TRANSPARENT);
        }

        checkStatus.put(position, isChecked);
        updateAcceptButton();
    }

    @Override
    public Loader<Cursor> onCreateLoader(int cursorID, Bundle arg1) {
        CursorLoader cl = null;

        ArrayList<String> img = new ArrayList<String>();
        switch (cursorID) {

        case CURSORLOADER_THUMBS:
            img.add(MediaStore.Images.Media._ID);
            img.add(MediaStore.Images.Media.ORIENTATION);
            break;
        case CURSORLOADER_REAL:
            img.add(MediaStore.Images.Thumbnails.DATA);
            img.add(MediaStore.Images.Media.ORIENTATION);
            break;
        default:
            break;
        }

        cl = new CursorLoader(MultiImageChooserActivity.this, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                img.toArray(new String[img.size()]), null, null, "DATE_MODIFIED DESC");
        return cl;
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
        if (cursor == null) {
            // NULL cursor. This usually means there's no image database yet....
            return;
        }

        switch (loader.getId()) {
            case CURSORLOADER_THUMBS:
                imagecursor = cursor;
                image_column_index = imagecursor.getColumnIndex(MediaStore.Images.Media._ID);
                image_column_orientation = imagecursor.getColumnIndex(MediaStore.Images.Media.ORIENTATION);
                ia.notifyDataSetChanged();
                break;
            case CURSORLOADER_REAL:
                actualimagecursor = cursor;
                String[] columns = actualimagecursor.getColumnNames();
                actual_image_column_index = actualimagecursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
                orientation_column_index = actualimagecursor.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION);
                break;
            default:
                break;
        }
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        if (loader.getId() == CURSORLOADER_THUMBS) {
            imagecursor = null;
        } else if (loader.getId() == CURSORLOADER_REAL) {
            actualimagecursor = null;
        }
    }
    
    public void cancelClicked(View ignored) {
        setResult(RESULT_CANCELED);
        finish();
    }

    public void selectClicked(View ignored) {
        ((TextView) getActionBar().getCustomView().findViewById(fakeR.getId("id", "actionbar_done_textview"))).setEnabled(false);
        getActionBar().getCustomView().findViewById(fakeR.getId("id", "actionbar_done")).setEnabled(false);
        progress.show();
        Intent data = new Intent();
        if (fileNames.isEmpty()) {
            this.setResult(RESULT_CANCELED);
            progress.dismiss();
            finish();
        } else {
            new ResizeImagesTask().execute(fileNames.entrySet());
        }
    }
    
    
    /*********************
     * Helper Methods
     ********************/
    private void updateAcceptButton() {
        ((TextView) getActionBar().getCustomView().findViewById(fakeR.getId("id", "actionbar_done_textview")))
                .setEnabled(fileNames.size() != 0);
        getActionBar().getCustomView().findViewById(fakeR.getId("id", "actionbar_done")).setEnabled(fileNames.size() != 0);
    }

    private void setupHeader() {
        // From Roman Nkk's code
        // https://plus.google.com/113735310430199015092/posts/R49wVvcDoEW
        // Inflate a "Done/Discard" custom action bar view
        /*
         * Copyright 2013 The Android Open Source Project
         *
         * Licensed under the Apache License, Version 2.0 (the "License");
         * you may not use this file except in compliance with the License.
         * You may obtain a copy of the License at
         *
         *     http://www.apache.org/licenses/LICENSE-2.0
         *
         * Unless required by applicable law or agreed to in writing, software
         * distributed under the License is distributed on an "AS IS" BASIS,
         * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
         * See the License for the specific language governing permissions and
         * limitations under the License.
         */
        LayoutInflater inflater = (LayoutInflater) getActionBar().getThemedContext().getSystemService(
                LAYOUT_INFLATER_SERVICE);
        final View customActionBarView = inflater.inflate(fakeR.getId("layout", "actionbar_custom_view_done_discard"), null);
        customActionBarView.findViewById(fakeR.getId("id", "actionbar_done")).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // "Done"
                selectClicked(null);
            }
        });
        customActionBarView.findViewById(fakeR.getId("id", "actionbar_discard")).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        // Show the custom action bar view and hide the normal Home icon and title.
        final ActionBar actionBar = getActionBar();
        actionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM, ActionBar.DISPLAY_SHOW_CUSTOM
                | ActionBar.DISPLAY_SHOW_HOME | ActionBar.DISPLAY_SHOW_TITLE);
        actionBar.setCustomView(customActionBarView, new ActionBar.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private String getImageName(int position) {
        actualimagecursor.moveToPosition(position);
        String name = null;

        try {
            name = actualimagecursor.getString(actual_image_column_index);
        } catch (Exception e) {
            return null;
        }
        return name;
    }
    
    private int getImageRotation(int position) {
        actualimagecursor.moveToPosition(position);
        int rotation = 0;

        try {
            rotation = actualimagecursor.getInt(orientation_column_index);
        } catch (Exception e) {
            return rotation;
        }
        return rotation;
    }
    
    public boolean isChecked(int position) {
        boolean ret = checkStatus.get(position);
        return ret;
    }

    
    /*********************
    * Nested Classes
    ********************/
    private class SquareImageView extends ImageView {
        public SquareImageView(Context context) {
			super(context);
		}

		@Override
        public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec);
        }
    }
    
    
    private class ImageAdapter extends BaseAdapter {
        private final Bitmap mPlaceHolderBitmap;

        public ImageAdapter(Context c) {
            Bitmap tmpHolderBitmap = BitmapFactory.decodeResource(getResources(), fakeR.getId("drawable", "loading_icon"));
            mPlaceHolderBitmap = Bitmap.createScaledBitmap(tmpHolderBitmap, colWidth, colWidth, false);
            if (tmpHolderBitmap != mPlaceHolderBitmap) {
                tmpHolderBitmap.recycle();
                tmpHolderBitmap = null;
            }
        }

        public int getCount() {
            if (imagecursor != null) {
                return imagecursor.getCount();
            } else {
                return 0;
            }
        }

        public Object getItem(int position) {
            return position;
        }

        public long getItemId(int position) {
            return position;
        }

        // create a new ImageView for each item referenced by the Adapter
        public View getView(int pos, View convertView, ViewGroup parent) {

            if (convertView == null) {
                ImageView temp = new SquareImageView(MultiImageChooserActivity.this);
                temp.setScaleType(ImageView.ScaleType.CENTER_CROP);
                convertView = (View)temp;
            }

            ImageView imageView = (ImageView)convertView;
            imageView.setImageBitmap(null);

            final int position = pos;

            if (!imagecursor.moveToPosition(position)) {
                return imageView;
            }

            if (image_column_index == -1) {
                return imageView;
            }

            final int id = imagecursor.getInt(image_column_index);
            final int rotate = imagecursor.getInt(image_column_orientation);
            if (isChecked(pos)) {
                if (android.os.Build.VERSION.SDK_INT>=16) {
                  imageView.setImageAlpha(128);
                } else {
                  imageView.setAlpha(128);	
                }
                imageView.setBackgroundColor(selectedColor);
            } else {
                if (android.os.Build.VERSION.SDK_INT>=16) {
                  imageView.setImageAlpha(255);
                } else {
                  imageView.setAlpha(255);	
                }
                imageView.setBackgroundColor(Color.TRANSPARENT);
            }
            if (shouldRequestThumb) {
                fetcher.fetch(Integer.valueOf(id), imageView, colWidth, rotate);
            }

            return imageView;
        }
    }
    
    private class ResizeImagesTask extends AsyncTask<Set<Entry<String, Integer>>, Void, ArrayList<ResizedImage>> {
        private Exception asyncTaskError = null;

        @Override
        protected ArrayList<ResizedImage> doInBackground(Set<Entry<String, Integer>>... fileSets) {
            Set<Entry<String, Integer>> fileNames = fileSets[0];
            ArrayList<ResizedImage> result = new ArrayList<ResizedImage>();
            try {
                Iterator<Entry<String, Integer>> i = fileNames.iterator();
                ScaledBitmap bmp;
                while(i.hasNext()) {
                    Entry<String, Integer> imageInfo = i.next();
                    File originalFile = new File(imageInfo.getKey());
                    int rotate = imageInfo.getValue().intValue();

                    try {
                        bmp = this.tryToGetBitmap(originalFile, rotate);
                    } catch (OutOfMemoryError e) {
                        throw new IOException("Unable to load image into memory.");
                    }

                    File scaledFile = this.storeImage(bmp.bitmap, "scaled_" + originalFile.getName());
                    result.add(new ResizedImage(originalFile, scaledFile, bmp.scaleFactor));
                }
                return result;
            } catch(IOException e) {
                try {
                    asyncTaskError = e;
                    for (final ResizedImage resizedImage: result) {
                        new File(new URI(resizedImage.getResizedImageUri())).delete();
                    }
                } catch(Exception exception) {
                    // the finally does what we want to do
                } finally {
                    return new ArrayList<ResizedImage>();
                }
            }
        }
        
        @Override
        protected void onPostExecute(ArrayList<ResizedImage> al) {
            Intent data = new Intent();

            if (asyncTaskError != null) {
                Bundle res = new Bundle();
                res.putString("ERRORMESSAGE", asyncTaskError.getMessage());
                data.putExtras(res);
                setResult(RESULT_CANCELED, data);
            } else if (al.size() > 0) {
                Bundle res = new Bundle();
                res.putStringArrayList("ORIGINAL_IMAGES", getOriginalImagesPaths(al));
                res.putStringArrayList("RESIZED_IMAGES", getResizedImagesPaths(al));
                res.putFloatArray("SCALE_FACTORS", getScaleFactors(al));
                if (imagecursor != null) {
                    res.putInt("TOTALFILES", imagecursor.getCount());
                }
                data.putExtras(res);
                setResult(RESULT_OK, data);
            } else {
                setResult(RESULT_CANCELED, data);
            }

            progress.dismiss();
            finish();
        }

        private ArrayList<String> getResizedImagesPaths(ArrayList<ResizedImage> images) {
            ArrayList<String> paths = new ArrayList<String>();
            for (ResizedImage image: images) {
                paths.add(image.getResizedImageUri());
            }
            return paths;
        }

        private ArrayList<String> getOriginalImagesPaths(ArrayList<ResizedImage> images) {
            ArrayList<String> paths = new ArrayList<String>();
            for (ResizedImage image: images) {
                paths.add(image.getOriginalImageUri());
            }
            return paths;
        }

        private float[] getScaleFactors(ArrayList<ResizedImage> images) {
            float[] factors = new float[images.size()];
            for (int i = 0; i < factors.length; i++) {
                factors[i] = images.get(i).getScaleFactor();
            }
            return factors;
        }

        private ScaledBitmap tryToGetBitmap(File file, int rotate) throws IOException, OutOfMemoryError {
            Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
            float scale = calculateScale(bmp.getWidth(), bmp.getHeight());

            if (bmp == null) {
                throw new IOException("The image file could not be opened.");
            }
            if (scale < 1) { // should scale?
                bmp = this.getResizedBitmap(bmp, scale);
            }
            if (rotate != 0) {
                Matrix matrix = new Matrix();
                matrix.setRotate(rotate);
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
            }
            return new ScaledBitmap(bmp, scale);
        }
        
        /*
        * The following functions are originally from
        * https://github.com/raananw/PhoneGap-Image-Resizer
        * 
        * They have been modified by Andrew Stephan for Sync OnSet
        *
        * The software is open source, MIT Licensed.
        * Copyright (C) 2012, webXells GmbH All Rights Reserved.
        */
        private File storeImage(Bitmap bmp, String fileName) throws IOException {
            int index = fileName.lastIndexOf('.');
            String name = fileName.substring(0, index);
            String ext = fileName.substring(index);
            File file = File.createTempFile("tmp_" + name, ext);
            OutputStream outStream = new FileOutputStream(file);
            if (ext.compareToIgnoreCase(".png") == 0) {
                bmp.compress(Bitmap.CompressFormat.PNG, quality, outStream);
            } else {
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, outStream);
            }
            outStream.flush();
            outStream.close();
            return file;
        }

        // http://stackoverflow.com/a/7468636/577812
        private Bitmap getResizedBitmap(Bitmap bm, float factor) {
            final int newWidth = (int) (bm.getWidth() * factor);
            final int newHeight = (int) (bm.getHeight() * factor);

            final Bitmap scaledBitmap = Bitmap.createBitmap(newWidth, newHeight, bm.getConfig());

            final Matrix scaleMatrix = new Matrix();
            scaleMatrix.setScale(factor, factor);

            final Canvas canvas = new Canvas(scaledBitmap);
            canvas.setMatrix(scaleMatrix);
            canvas.drawBitmap(bm, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));

            return scaledBitmap;
        }
    }
    
    private float calculateScale(int width, int height) {
        float widthScale = 1.0f;
        float heightScale = 1.0f;
        float scale = 1.0f;
        if (desiredWidth > 0 || desiredHeight > 0) {
            if (desiredHeight == 0 && desiredWidth < width) {
                scale = (float)desiredWidth/width;
            } else if (desiredWidth == 0 && desiredHeight < height) {
                scale = (float)desiredHeight/height;
            } else {
                if (desiredWidth > 0 && desiredWidth < width) {
                    widthScale = (float)desiredWidth/width;
                }
                if (desiredHeight > 0 && desiredHeight < height) {
                    heightScale = (float)desiredHeight/height;
                }
                if (widthScale > heightScale) {
                    scale = widthScale;
                } else {
                    scale = heightScale;
                }
            }
        }

        return scale;
    }
}
