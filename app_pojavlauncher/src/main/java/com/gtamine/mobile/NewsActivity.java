package com.gtamine.mobile;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Native, card-based reader for the public GTAMINE Telegram feed. */
public class NewsActivity extends BaseActivity {
    private static final String CHANNEL_URL = "https://t.me/s/gtamine";
    private static final String POST_URL_PREFIX = "https://t.me/";
    private static final Pattern POST_BLOCK = Pattern.compile("(?s)<div class=\"tgme_widget_message[^>]*data-post=\"([^\"]+)\"[^>]*>(.*?)(?=<div class=\"tgme_widget_message_wrap|<div class=\"tgme_footer|</main>)");
    private static final Pattern POST_TEXT = Pattern.compile("(?s)<div class=\"tgme_widget_message_text js-message_text\"[^>]*>(.*?)</div>");
    private static final Pattern POST_DATE = Pattern.compile("<time datetime=\"([^\"]+)\"");
    private static final Pattern PHOTO_STYLE = Pattern.compile("tgme_widget_message_photo_wrap[^>]*style=\"([^\"]+)\"");
    private static final Pattern IMAGE_URL = Pattern.compile("url\\(['\\\"]?([^'\\\")]+)");
    private final List<NewsPost> posts = new ArrayList<>();
    private NewsAdapter adapter;
    private ProgressBar loadingView;
    private TextView errorView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_news);

        RecyclerView list = findViewById(R.id.news_list);
        loadingView = findViewById(R.id.news_loading);
        errorView = findViewById(R.id.news_error);
        adapter = new NewsAdapter();
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        ImageButton backButton = findViewById(R.id.news_back_button);
        ImageButton refreshButton = findViewById(R.id.news_refresh_button);
        backButton.setOnClickListener(view -> finish());
        refreshButton.setOnClickListener(view -> loadNews());
        loadNews();
    }

    private void loadNews() {
        loadingView.setVisibility(View.VISIBLE);
        errorView.setVisibility(View.GONE);
        new Thread(() -> {
            try {
                List<NewsPost> loadedPosts = fetchPosts();
                runOnUiThread(() -> showPosts(loadedPosts));
            } catch (Exception exception) {
                runOnUiThread(this::showLoadError);
            }
        }, "GTAMINE-News").start();
    }

    private void showPosts(List<NewsPost> loadedPosts) {
        if (isFinishing()) return;
        loadingView.setVisibility(View.GONE);
        posts.clear();
        posts.addAll(loadedPosts);
        adapter.notifyDataSetChanged();
        if (posts.isEmpty()) showLoadError();
    }

    private void showLoadError() {
        if (isFinishing()) return;
        loadingView.setVisibility(View.GONE);
        if (posts.isEmpty()) errorView.setVisibility(View.VISIBLE);
    }

    private List<NewsPost> fetchPosts() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(CHANNEL_URL).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) GTAMINE-Mobile/1.0");
        connection.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9");
        try (InputStream stream = new BufferedInputStream(connection.getInputStream())) {
            String html = readHtml(stream);
            Matcher postMatcher = POST_BLOCK.matcher(html);
            List<NewsPost> result = new ArrayList<>();
            while (postMatcher.find()) {
                NewsPost post = parsePost(postMatcher.group(1), postMatcher.group(2));
                if (post != null) result.add(post);
            }
            // Telegram's public page lists posts from oldest to newest.
            // The launcher feed should always open on the latest publication.
            Collections.reverse(result);
            if (result.size() > 12) return new ArrayList<>(result.subList(0, 12));
            return result;
        } finally {
            connection.disconnect();
        }
    }

    @Nullable
    private NewsPost parsePost(String postId, String content) {
        Matcher textMatcher = POST_TEXT.matcher(content);
        String text = textMatcher.find() ? cleanText(Html.fromHtml(textMatcher.group(1)).toString()) : "";
        Matcher dateMatcher = POST_DATE.matcher(content);
        String date = dateMatcher.find() ? formatDate(dateMatcher.group(1)) : "";
        Matcher photoMatcher = PHOTO_STYLE.matcher(content);
        String imageUrl = photoMatcher.find() ? getImageUrl(photoMatcher.group(1)) : null;
        if (text.isEmpty() && imageUrl == null) return null;
        return new NewsPost(text, date, imageUrl, POST_URL_PREFIX + postId);
    }

    private String readHtml(InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) != -1) output.write(buffer, 0, read);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private String cleanText(String text) {
        return text.replace('\u00a0', ' ').replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\\n\\n").trim();
    }

    private String getImageUrl(String style) {
        if (style == null) return null;
        Matcher matcher = IMAGE_URL.matcher(style);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String formatDate(String source) {
        if (source == null || source.isEmpty()) return "";
        try {
            SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
            parser.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = parser.parse(source);
            return new SimpleDateFormat("d MMMM, HH:mm", new Locale("ru")).format(date);
        } catch (Exception ignored) {
            return "";
        }
    }

    private final class NewsAdapter extends RecyclerView.Adapter<NewsAdapter.PostHolder> {
        @NonNull
        @Override
        public PostHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_news_post, parent, false);
            return new PostHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull PostHolder holder, int position) {
            NewsPost post = posts.get(position);
            holder.text.setText(post.text);
            holder.date.setText(post.date);
            holder.open.setOnClickListener(view -> openPost(post.url));
            holder.itemView.setOnClickListener(view -> openPost(post.url));
            holder.image.setImageBitmap(null);
            holder.image.setVisibility(post.imageUrl == null ? View.GONE : View.VISIBLE);
            if (post.imageUrl != null) {
                ViewGroup.LayoutParams imageParams = holder.image.getLayoutParams();
                imageParams.height = dp(128);
                holder.image.setLayoutParams(imageParams);
                loadImage(holder.image, post.imageUrl);
                holder.content.post(() -> fitImageToCard(holder));
            }
        }

        @Override
        public int getItemCount() {
            return posts.size();
        }

        final class PostHolder extends RecyclerView.ViewHolder {
            final ImageView image;
            final View content;
            final TextView text;
            final TextView date;
            final TextView open;

            PostHolder(View itemView) {
                super(itemView);
                image = itemView.findViewById(R.id.news_post_image);
                content = itemView.findViewById(R.id.news_post_content);
                text = itemView.findViewById(R.id.news_post_text);
                date = itemView.findViewById(R.id.news_post_date);
                open = itemView.findViewById(R.id.news_post_open);
            }
        }
    }

    private void fitImageToCard(NewsAdapter.PostHolder holder) {
        if (holder.image.getVisibility() != View.VISIBLE) return;
        int cardHeight = holder.content.getHeight();
        if (cardHeight <= 0 || holder.image.getHeight() == cardHeight) return;
        ViewGroup.LayoutParams imageParams = holder.image.getLayoutParams();
        imageParams.height = cardHeight;
        holder.image.setLayoutParams(imageParams);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void loadImage(ImageView target, String imageUrl) {
        target.setTag(imageUrl);
        new Thread(() -> {
            Bitmap bitmap = downloadBitmap(imageUrl);
            if (bitmap == null) return;
            runOnUiThread(() -> {
                if (imageUrl.equals(target.getTag())) target.setImageBitmap(bitmap);
            });
        }, "GTAMINE-NewsImage").start();
    }

    @Nullable
    private Bitmap downloadBitmap(String imageUrl) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(imageUrl).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            try (InputStream stream = connection.getInputStream()) {
                return BitmapFactory.decodeStream(stream);
            }
        } catch (IOException ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void openPost(String url) {
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) { }
    }

    private static final class NewsPost {
        final String text;
        final String date;
        final String imageUrl;
        final String url;

        NewsPost(String text, String date, String imageUrl, String url) {
            this.text = text;
            this.date = date;
            this.imageUrl = imageUrl;
            this.url = url;
        }
    }
}
