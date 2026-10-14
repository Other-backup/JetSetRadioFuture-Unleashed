package org.libsdl.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Android import screen. It uses the Storage Access Framework so the player
 * chooses their own game copy without granting broad storage permissions.
 */
public final class JSRFLauncherActivity extends Activity {
    private static final int REQUEST_GAME_TREE = 1001;
    private static final int REQUEST_DISC_IMAGE = 1002;
    private static final int COPY_BUFFER_SIZE = 64 * 1024;
    private static final int MAX_TREE_DEPTH = 64;
    private static final int MAX_FILES = 250000;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView statusView;
    private ProgressBar progressBar;
    private Button folderButton;
    private Button imageButton;
    private Button launchButton;
    private boolean busy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        refreshState();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(13, 17, 27));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(24);
        page.setPadding(pad, dp(34), pad, dp(24));
        scroll.addView(page);

        TextView title = new TextView(this);
        title.setText("JET SET RADIO FUTURE");
        title.setTextColor(Color.rgb(105, 220, 255));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextSize(25);
        title.setGravity(Gravity.CENTER);
        page.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("UNLEASHED · ANDROID");
        subtitle.setTextColor(Color.LTGRAY);
        subtitle.setTextSize(13);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subParams = matchWrap();
        subParams.topMargin = dp(8);
        subParams.bottomMargin = dp(26);
        page.addView(subtitle, subParams);

        TextView explanation = new TextView(this);
        explanation.setText("Importe sua cópia de Jet Set Radio Future. Você pode escolher a pasta que contém default.xbe ou uma imagem de disco ISO/XISO.");
        explanation.setTextColor(Color.WHITE);
        explanation.setTextSize(16);
        explanation.setLineSpacing(dp(3), 1.0f);
        explanation.setGravity(Gravity.CENTER);
        page.addView(explanation, matchWrap());

        folderButton = makeButton("Selecionar pasta do jogo");
        folderButton.setOnClickListener(v -> chooseGameFolder());
        addButton(page, folderButton, dp(26));

        imageButton = makeButton("Importar ISO / XISO");
        imageButton.setOnClickListener(v -> chooseDiscImage());
        addButton(page, imageButton, dp(10));

        progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                dp(42), dp(42));
        progressParams.topMargin = dp(24);
        page.addView(progressBar, progressParams);

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(205, 213, 225));
        statusView.setTextSize(14);
        statusView.setGravity(Gravity.CENTER);
        statusView.setLineSpacing(dp(3), 1.0f);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(18);
        page.addView(statusView, statusParams);

        launchButton = makeButton("Jogar");
        launchButton.setTextColor(Color.WHITE);
        launchButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Color.rgb(0, 142, 190)));
        launchButton.setOnClickListener(v -> launchGame());
        addButton(page, launchButton, dp(26));

        TextView footer = new TextView(this);
        footer.setText("Versão experimental · Requer um dispositivo ARM64 compatível com OpenGL ES 3.2");
        footer.setTextColor(Color.GRAY);
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerParams = matchWrap();
        footerParams.topMargin = dp(22);
        page.addView(footer, footerParams);

        setContentView(scroll);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void addButton(LinearLayout page, Button button, int topMargin) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = topMargin;
        params.height = dp(52);
        page.addView(button, params);
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setMinHeight(dp(48));
        return button;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private File storageBase() throws IOException {
        File base = getExternalFilesDir(null);
        if (base == null) {
            base = getFilesDir();
        }
        if (!base.isDirectory() && !base.mkdirs()) {
            throw new IOException("Não foi possível criar a pasta privada do aplicativo.");
        }
        return base;
    }

    private File gameDirectory() throws IOException {
        return new File(storageBase(), "game");
    }

    private boolean hasGameFiles() {
        try {
            File xbe = new File(gameDirectory(), "default.xbe");
            return xbe.isFile() && xbe.length() > 0;
        } catch (IOException ex) {
            return false;
        }
    }

    private void refreshState() {
        if (busy) {
            return;
        }
        boolean hasGame = hasGameFiles();
        folderButton.setEnabled(true);
        imageButton.setEnabled(true);
        launchButton.setEnabled(BuildConfig.JSRF_RUNTIME_AVAILABLE && hasGame);

        if (!BuildConfig.JSRF_RUNTIME_AVAILABLE) {
            statusView.setText((hasGame
                    ? "Arquivos do jogo importados. "
                    : "Nenhum jogo importado. ")
                    + "Esta build contém o importador, mas ainda não inclui o runtime compilado. "
                    + "Para jogar, é necessário gerar o código nativo localmente e reconstruir o APK.");
        } else if (hasGame) {
            statusView.setText("Jogo importado. Pronto para iniciar.");
        } else {
            statusView.setText("Selecione a pasta que contém diretamente o arquivo default.xbe ou escolha uma ISO/XISO.");
        }
    }

    private void chooseGameFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_GAME_TREE);
    }

    private void chooseDiscImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "application/octet-stream", "application/x-iso9660-image", "*/*"
        });
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_DISC_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some document providers grant a temporary read permission only.
        }

        if (requestCode == REQUEST_GAME_TREE) {
            DocumentFile root = DocumentFile.fromTreeUri(this, uri);
            if (root == null || !root.isDirectory()
                    || findChildIgnoreCase(root, "default.xbe") == null) {
                showError("Pasta inválida",
                        "Selecione a pasta do jogo que contém default.xbe diretamente nela. Não selecione a pasta pai.");
                return;
            }
            importFolder(root);
        } else if (requestCode == REQUEST_DISC_IMAGE) {
            String name = displayName(uri);
            String lower = name.toLowerCase(Locale.ROOT);
            if (!(lower.endsWith(".iso") || lower.endsWith(".xiso")
                    || lower.endsWith(".bin"))) {
                showError("Formato não reconhecido",
                        "Selecione uma imagem de disco .iso ou .xiso compatível com Xbox.");
                return;
            }
            importDiscImage(uri, name);
        }
    }

    private void importFolder(DocumentFile source) {
        beginBusy("Copiando os arquivos do jogo…");
        worker.execute(() -> {
            File staging = null;
            try {
                File base = storageBase();
                staging = new File(base, "game.importing");
                deleteRecursively(staging);
                if (!staging.mkdirs() && !staging.isDirectory()) {
                    throw new IOException("Não foi possível criar a pasta temporária.");
                }
                int[] count = {0};
                copyTree(source, staging, 0, true, count);
                validateGame(staging);
                installStaging(staging, new File(base, "game"));
                finishTask(null, "Importação concluída. " + count[0] + " arquivos copiados.");
            } catch (Exception ex) {
                if (staging != null) {
                    deleteRecursively(staging);
                }
                finishTask(errorText(ex), null);
            }
        });
    }

    private void importDiscImage(Uri sourceUri, String sourceName) {
        beginBusy("Copiando a imagem de disco…");
        worker.execute(() -> {
            File staging = null;
            File image = null;
            try {
                File base = storageBase();
                image = new File(base, "jsrf-import-image.iso");
                staging = new File(base, "game.importing");
                deleteRecursively(image);
                deleteRecursively(staging);

                copyUriToFile(sourceUri, image, sourceName);
                updateStatus("Extraindo a imagem Xbox. Isso pode levar alguns minutos…");
                if (!staging.mkdirs() && !staging.isDirectory()) {
                    throw new IOException("Não foi possível criar a pasta temporária.");
                }
                String error = XisoNative.extractXiso(image.getAbsolutePath(),
                        staging.getAbsolutePath());
                if (error != null && !error.isEmpty()) {
                    throw new IOException(error);
                }
                validateGame(staging);
                installStaging(staging, new File(base, "game"));
                finishTask(null, "Imagem extraída e jogo importado.");
            } catch (Exception ex) {
                if (staging != null) {
                    deleteRecursively(staging);
                }
                finishTask(errorText(ex), null);
            } finally {
                if (image != null) {
                    deleteRecursively(image);
                }
            }
        });
    }

    private void copyUriToFile(Uri uri, File destination, String name) throws IOException {
        updateStatus("Copiando " + name + "…");
        try (InputStream raw = getContentResolver().openInputStream(uri)) {
            if (raw == null) {
                throw new IOException("O Android não conseguiu abrir essa imagem.");
            }
            try (InputStream in = new BufferedInputStream(raw);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(destination))) {
                byte[] buffer = new byte[COPY_BUFFER_SIZE];
                int read;
                long bytes = 0;
                long lastUpdate = 0;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    bytes += read;
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastUpdate > 1000) {
                        long current = bytes;
                        updateStatus("Copiando imagem… " + (current / (1024 * 1024)) + " MiB");
                        lastUpdate = now;
                    }
                }
            }
        } catch (IOException ex) {
            deleteRecursively(destination);
            throw ex;
        }
    }

    private void copyTree(DocumentFile source, File destination, int depth,
                          boolean root, int[] fileCount) throws IOException {
        if (depth > MAX_TREE_DEPTH) {
            throw new IOException("A estrutura de pastas é profunda demais.");
        }
        DocumentFile[] children = source.listFiles();
        for (DocumentFile child : children) {
            String originalName = child.getName();
            if (originalName == null || originalName.isEmpty()
                    || ".".equals(originalName) || "..".equals(originalName)
                    || originalName.contains("/") || originalName.contains("\\")) {
                throw new IOException("O jogo contém um nome de arquivo inválido.");
            }
            String name = root && child.isFile()
                    && "default.xbe".equalsIgnoreCase(originalName)
                    ? "default.xbe" : originalName;
            File outFile = new File(destination, name);
            if (child.isDirectory()) {
                if (!outFile.isDirectory() && !outFile.mkdirs()) {
                    throw new IOException("Não foi possível criar " + name);
                }
                copyTree(child, outFile, depth + 1, false, fileCount);
            } else if (child.isFile()) {
                fileCount[0]++;
                if (fileCount[0] > MAX_FILES) {
                    throw new IOException("A pasta contém arquivos demais para importar com segurança.");
                }
                copyDocumentFile(child, outFile);
                if ((fileCount[0] % 20) == 0) {
                    updateStatus("Copiando arquivos… " + fileCount[0]);
                }
            }
        }
    }

    private void copyDocumentFile(DocumentFile source, File destination) throws IOException {
        try (InputStream raw = getContentResolver().openInputStream(source.getUri())) {
            if (raw == null) {
                throw new IOException("Não foi possível ler " + source.getName());
            }
            try (InputStream in = new BufferedInputStream(raw);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(destination))) {
                byte[] buffer = new byte[COPY_BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
        } catch (IOException ex) {
            deleteRecursively(destination);
            throw ex;
        }
    }

    private void validateGame(File directory) throws IOException {
        File xbe = new File(directory, "default.xbe");
        if (!xbe.isFile()) {
            // XDVDFS is case-insensitive, but the Android filesystem is not.
            // Normalize the required executable name so the native loader can open it.
            File[] entries = directory.listFiles();
            if (entries != null) {
                for (File entry : entries) {
                    if (entry.isFile() && "default.xbe".equalsIgnoreCase(entry.getName())) {
                        if (!entry.renameTo(xbe)) {
                            throw new IOException("Não foi possível normalizar o nome de default.xbe.");
                        }
                        break;
                    }
                }
            }
        }
        if (!xbe.isFile() || xbe.length() == 0) {
            throw new IOException("Não encontrei um default.xbe válido na raiz dos arquivos importados.");
        }
    }

    private void installStaging(File staging, File destination) throws IOException {
        File base = destination.getParentFile();
        File backup = new File(base, "game.backup");
        deleteRecursively(backup);
        boolean hadPrevious = destination.exists();
        if (hadPrevious && !destination.renameTo(backup)) {
            throw new IOException("Não foi possível substituir os arquivos do jogo existente.");
        }
        if (!staging.renameTo(destination)) {
            if (hadPrevious) {
                // Best-effort rollback if the final rename fails.
                backup.renameTo(destination);
            }
            throw new IOException("Não foi possível finalizar a importação. Verifique o espaço livre.");
        }
        deleteRecursively(backup);
    }

    private DocumentFile findChildIgnoreCase(DocumentFile directory, String expected) {
        for (DocumentFile child : directory.listFiles()) {
            if (expected.equalsIgnoreCase(child.getName())) {
                return child;
            }
        }
        return null;
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (col >= 0) {
                    String value = cursor.getString(col);
                    if (value != null) {
                        return value;
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through to the URI's last path segment.
        }
        String path = uri.getLastPathSegment();
        return path == null ? "disc image" : path;
    }

    private void beginBusy(String message) {
        runOnUiThread(() -> {
            busy = true;
            progressBar.setVisibility(View.VISIBLE);
            folderButton.setEnabled(false);
            imageButton.setEnabled(false);
            launchButton.setEnabled(false);
            statusView.setText(message);
        });
    }

    private void updateStatus(String message) {
        runOnUiThread(() -> statusView.setText(message));
    }

    private void finishTask(String error, String success) {
        runOnUiThread(() -> {
            busy = false;
            progressBar.setVisibility(View.GONE);
            refreshState();
            if (error != null) {
                statusView.setText("A importação falhou.");
                showError("Não foi possível importar", error);
            } else if (success != null) {
                statusView.setText(success);
            }
        });
    }

    private String errorText(Exception ex) {
        String value = ex.getMessage();
        return value == null || value.trim().isEmpty()
                ? "Erro inesperado durante a importação." : value;
    }

    private void showError(String title, String message) {
        if (isFinishing()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private void launchGame() {
        if (!BuildConfig.JSRF_RUNTIME_AVAILABLE) {
            showError("Runtime não incluído",
                    "Esta build valida o importador, mas não contém o código nativo do jogo. Gere o código localmente e compile o APK completo.");
            return;
        }
        if (!hasGameFiles()) {
            showError("Jogo não encontrado", "Importe primeiro uma pasta ou imagem de disco compatível.");
            refreshState();
            return;
        }
        startActivity(new Intent(this, JSRFActivity.class));
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        // A failed delete is caught by the following mkdir/copy/rename checks.
        file.delete();
    }
}
