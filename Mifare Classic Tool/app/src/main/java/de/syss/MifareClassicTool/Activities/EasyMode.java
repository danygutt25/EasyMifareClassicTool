/*
 * EasyMifareClassicTool - simplified UI layer for MIFARE Classic Tool.
 *
 * This file is distributed under the GNU GPL v3 or later, consistently
 * with the upstream MIFARE Classic Tool project.
 */
package de.syss.MifareClassicTool.Activities;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import de.syss.MifareClassicTool.Common;
import de.syss.MifareClassicTool.MCReader;
import de.syss.MifareClassicTool.R;

/**
 * Beginner-friendly front end that reuses MCT's existing key mapping and
 * tag reading code. The original MainMenu remains available as Advanced mode.
 *
 * The balance parser is deliberately profile-like and read-only for physical
 * tags. The edit dialog changes only the displayed test value; no NFC write is
 * performed here.
 */
public class EasyMode extends BasicActivity {

    private static final int KEY_MAP_CREATOR = 1001;
    private static final int IMPORT_KEYS = 1002;

    // Default laboratory profile. These values can be changed later when the
    // exact laboratory card layout is confirmed.
    private static final int BALANCE_SECTOR = 4;
    private static final int BALANCE_LINE = 1; // second data line/block in sector
    private static final int BALANCE_OFFSET = 0;
    private static final int BALANCE_LENGTH = 2;
    private static final boolean BALANCE_LITTLE_ENDIAN = true;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Spinner keyFileSpinner;
    private TextView statusText;
    private TextView balanceText;
    private TextView technicalText;
    private LinearLayout balancePanel;
    private Button readButton;
    private final List<File> keyFiles = new ArrayList<>();
    private long displayedCents = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_easy_mode);

        keyFileSpinner = findViewById(R.id.spinnerEasyKeyFile);
        statusText = findViewById(R.id.textEasyStatus);
        balanceText = findViewById(R.id.textEasyBalance);
        technicalText = findViewById(R.id.textEasyBalanceTechnical);
        balancePanel = findViewById(R.id.panelEasyBalance);
        readButton = findViewById(R.id.buttonEasyRead);

        ensureFolders();
        refreshKeyFiles();

        findViewById(R.id.buttonEasyImportKeys).setOnClickListener(v -> chooseKeyTextFile());
        findViewById(R.id.buttonEasyRefreshKeys).setOnClickListener(v -> refreshKeyFiles());
        readButton.setOnClickListener(v -> beginEasyRead());
        findViewById(R.id.buttonEasyAdvanced).setOnClickListener(v ->
                startActivity(new Intent(this, MainMenu.class)));
        findViewById(R.id.buttonEasyEditBalance).setOnClickListener(v -> showTestBalanceEditor());

        // If Android launched this activity directly because a tag was tapped,
        // make the tag available to MCT's existing reader code.
        Intent intent = getIntent();
        if (intent != null && NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction())) {
            Common.treatAsNewTag(intent, this);
            statusText.setText("Tarjeta detectada. Pulsa Leer tarjeta.");
        }
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        statusText.setText("Tarjeta detectada. Pulsa Leer tarjeta.");
    }

    private void ensureFolders() {
        File keys = Common.getFile(Common.KEYS_DIR);
        if (!keys.exists()) {
            //noinspection ResultOfMethodCallIgnored
            keys.mkdirs();
        }
        File dumps = Common.getFile(Common.DUMPS_DIR);
        if (!dumps.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dumps.mkdirs();
        }
        File tmp = Common.getFile(Common.TMP_DIR);
        if (!tmp.exists()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.mkdirs();
        }
    }

    private void chooseKeyTextFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        startActivityForResult(intent, IMPORT_KEYS);
    }

    private void askKeyListName(Uri uri) {
        EditText input = new EditText(this);
        input.setHint("Ej. laboratorio");
        int pad = Common.dpToPx(18);
        input.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
                .setTitle("Nombre de la lista")
                .setMessage("Las keys del TXT se guardarán como una lista. No hace falta indicar A o B.")
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Guardar", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) name = "mis_keys";
                    name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
                    if (!name.endsWith(".keys")) name += ".keys";
                    if (importKeyFile(uri, new File(Common.getFile(Common.KEYS_DIR), name))) {
                        refreshKeyFiles();
                        Toast.makeText(this, "Lista de keys guardada.", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "No se pudo importar el TXT.", Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private boolean importKeyFile(Uri uri, File target) {
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) return false;
            Common.copyFile(in, out);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean prepareSingleKeyDirectory(File source, File targetDir) {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return false;
        }
        File[] oldFiles = targetDir.listFiles();
        if (oldFiles != null) {
            for (File old : oldFiles) {
                //noinspection ResultOfMethodCallIgnored
                old.delete();
            }
        }
        File target = new File(targetDir, source.getName());
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            Common.copyFile(in, out);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void refreshKeyFiles() {
        keyFiles.clear();
        File dir = Common.getFile(Common.KEYS_DIR);
        File[] files = dir.listFiles(file -> file.isFile());
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            keyFiles.addAll(Arrays.asList(files));
        }

        List<String> names = new ArrayList<>();
        for (File file : keyFiles) {
            names.add(file.getName());
        }
        if (names.isEmpty()) {
            names.add("No hay listas de keys");
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        keyFileSpinner.setAdapter(adapter);
        readButton.setEnabled(!keyFiles.isEmpty());
    }

    private void beginEasyRead() {
        if (keyFiles.isEmpty()) {
            Toast.makeText(this, "Añade primero una lista de keys.", Toast.LENGTH_LONG).show();
            return;
        }
        int pos = keyFileSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= keyFiles.size()) {
            return;
        }

        File selectedKeyFile = keyFiles.get(pos);
        File easyKeysDir = new File(Common.getFile(Common.TMP_DIR), "easy-selected-keys");
        if (!prepareSingleKeyDirectory(selectedKeyFile, easyKeysDir)) {
            Toast.makeText(this, "No se pudo preparar la lista de keys seleccionada.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        // Reuse MCT's normal KeyMapCreator, but give it a directory containing
        // only the list explicitly chosen in Easy Mode.
        Intent intent = new Intent(this, KeyMapCreator.class);
        intent.putExtra(KeyMapCreator.EXTRA_KEYS_DIR, easyKeysDir.getAbsolutePath());
        intent.putExtra(KeyMapCreator.EXTRA_AUTO_SELECT_SINGLE_KEY_FILE, true);
        intent.putExtra(KeyMapCreator.EXTRA_SECTOR_CHOOSER, false);
        intent.putExtra(KeyMapCreator.EXTRA_SECTOR_CHOOSER_FROM, BALANCE_SECTOR);
        intent.putExtra(KeyMapCreator.EXTRA_SECTOR_CHOOSER_TO, BALANCE_SECTOR);
        intent.putExtra(KeyMapCreator.EXTRA_TITLE, "Easy Mode · sector " + BALANCE_SECTOR);
        intent.putExtra(KeyMapCreator.EXTRA_BUTTON_TEXT, "Usar keys y leer saldo");
        statusText.setText("Preparando autenticación del sector " + BALANCE_SECTOR + "…");
        startActivityForResult(intent, KEY_MAP_CREATOR);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == IMPORT_KEYS) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                askKeyListName(data.getData());
            }
            return;
        }
        if (requestCode != KEY_MAP_CREATOR) {
            return;
        }
        if (resultCode != Activity.RESULT_OK || Common.getKeyMap() == null) {
            statusText.setText("No se pudo autenticar el sector con las keys disponibles.");
            return;
        }
        readBalanceFromTag();
    }

    private void readBalanceFromTag() {
        final MCReader reader = Common.checkForTagAndCreateReader(this);
        if (reader == null) {
            statusText.setText("Acerca de nuevo la tarjeta.");
            return;
        }
        statusText.setText("Leyendo saldo…");

        new Thread(() -> {
            SparseArray<String[]> dump = reader.readAsMuchAsPossible(Common.getKeyMap());
            reader.close();
            handler.post(() -> showBalanceFromDump(dump));
        }).start();
    }

    private void showBalanceFromDump(SparseArray<String[]> dump) {
        if (dump == null) {
            statusText.setText("La tarjeta se retiró durante la lectura.");
            return;
        }
        String[] sector = dump.get(BALANCE_SECTOR);
        if (sector == null || BALANCE_LINE < 0 || BALANCE_LINE >= sector.length) {
            statusText.setText("El sector 4 no se pudo leer con las keys seleccionadas.");
            return;
        }

        String hexLine = sector[BALANCE_LINE];
        try {
            byte[] block = hexToBytes(hexLine);
            displayedCents = decodeUnsigned(block, BALANCE_OFFSET, BALANCE_LENGTH,
                    BALANCE_LITTLE_ENDIAN);
            balanceText.setText(String.format(Locale.getDefault(), "%.2f €", displayedCents / 100.0));
            technicalText.setText("Sector " + BALANCE_SECTOR
                    + " · línea " + (BALANCE_LINE + 1)
                    + " · HEX " + selectedHex(block, BALANCE_OFFSET, BALANCE_LENGTH));
            balancePanel.setVisibility(View.VISIBLE);
            statusText.setText("Tarjeta leída correctamente");
        } catch (IllegalArgumentException ex) {
            statusText.setText("No se pudo interpretar el campo configurado como saldo.");
        }
    }

    private void showTestBalanceEditor() {
        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(String.format(Locale.US, "%.2f", displayedCents / 100.0));
        int pad = Common.dpToPx(18);
        input.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(this)
                .setTitle("Saldo de prueba")
                .setMessage("Esta pantalla solo cambia la vista local. No escribe el saldo de una tarjeta NFC real.")
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Aplicar", (dialog, which) -> {
                    try {
                        double euros = Double.parseDouble(input.getText().toString().replace(',', '.'));
                        if (euros < 0) throw new NumberFormatException();
                        displayedCents = Math.round(euros * 100.0);
                        balanceText.setText(String.format(Locale.getDefault(), "%.2f €", displayedCents / 100.0));
                        technicalText.setText("Vista local de prueba · tarjeta sin modificar");
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Introduce un importe válido.", Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private static byte[] hexToBytes(String value) {
        String hex = value.replaceAll("[^0-9A-Fa-f]", "");
        if (hex.length() != 32) {
            throw new IllegalArgumentException("Expected one 16-byte block");
        }
        byte[] out = new byte[16];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static long decodeUnsigned(byte[] data, int offset, int length, boolean littleEndian) {
        if (length < 1 || length > 4 || offset < 0 || offset + length > data.length) {
            throw new IllegalArgumentException("Invalid balance range");
        }
        long value = 0;
        for (int i = 0; i < length; i++) {
            int source = littleEndian ? offset + i : offset + (length - 1 - i);
            value |= ((long) data[source] & 0xFFL) << (8 * i);
        }
        return value;
    }

    private static String selectedHex(byte[] data, int offset, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", data[offset + i] & 0xFF));
        }
        return sb.toString();
    }
}
