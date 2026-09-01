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
 * tag read/write code. The original MainMenu remains available as Advanced mode.
 *
 * Easy Mode v2 contains one explicit laboratory profile:
 * sector 4, blocks 0 and 1 (absolute blocks 16 and 17) are duplicate MIFARE
 * Value Blocks containing the balance in cents.
 */
public class EasyMode extends BasicActivity {

    private static final int KEY_MAP_CREATOR = 1001;
    private static final int IMPORT_KEYS = 1002;

    // Laboratory profile confirmed from the user's test card dump.
    private static final int BALANCE_SECTOR = 4;
    private static final int BALANCE_BLOCK_A = 0; // absolute block 16
    private static final int BALANCE_BLOCK_B = 1; // absolute block 17

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Spinner keyFileSpinner;
    private TextView statusText;
    private TextView balanceText;
    private TextView technicalText;
    private LinearLayout balancePanel;
    private Button readButton;
    private Button editButton;
    private final List<File> keyFiles = new ArrayList<>();

    private long displayedCents = 0;
    private byte[] originalBlockA;
    private byte[] originalBlockB;
    private byte[] lastReadUid;

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
        editButton = findViewById(R.id.buttonEasyEditBalance);

        ensureFolders();
        refreshKeyFiles();

        findViewById(R.id.buttonEasyImportKeys).setOnClickListener(v -> chooseKeyTextFile());
        findViewById(R.id.buttonEasyRefreshKeys).setOnClickListener(v -> refreshKeyFiles());
        readButton.setOnClickListener(v -> beginEasyRead());
        findViewById(R.id.buttonEasyAdvanced).setOnClickListener(v ->
                startActivity(new Intent(this, MainMenu.class)));
        editButton.setOnClickListener(v -> showBalanceEditor());

        Intent intent = getIntent();
        if (intent != null && NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction())) {
            Common.treatAsNewTag(intent, this);
            statusText.setText("Tarjeta detectada. Pulsa Leer tarjeta.");
        }
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent); // BasicActivity updates Common's active tag/UID.
        statusText.setText("Tarjeta detectada. Pulsa Leer tarjeta.");
    }

    private void ensureFolders() {
        File keys = Common.getFile(Common.KEYS_DIR);
        if (!keys.exists()) keys.mkdirs();
        File dumps = Common.getFile(Common.DUMPS_DIR);
        if (!dumps.exists()) dumps.mkdirs();
        File tmp = Common.getFile(Common.TMP_DIR);
        if (!tmp.exists()) tmp.mkdirs();
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
        if (!targetDir.exists() && !targetDir.mkdirs()) return false;
        File[] oldFiles = targetDir.listFiles();
        if (oldFiles != null) {
            for (File old : oldFiles) old.delete();
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
        for (File file : keyFiles) names.add(file.getName());
        if (names.isEmpty()) names.add("No hay listas de keys");

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
        if (pos < 0 || pos >= keyFiles.size()) return;

        File selectedKeyFile = keyFiles.get(pos);
        File easyKeysDir = new File(Common.getFile(Common.TMP_DIR), "easy-selected-keys");
        if (!prepareSingleKeyDirectory(selectedKeyFile, easyKeysDir)) {
            Toast.makeText(this, "No se pudo preparar la lista de keys seleccionada.",
                    Toast.LENGTH_LONG).show();
            return;
        }

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
        if (requestCode != KEY_MAP_CREATOR) return;
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
            byte[] uid = Common.getUID() == null ? null : Common.getUID().clone();
            reader.close();
            handler.post(() -> showBalanceFromDump(dump, uid));
        }).start();
    }

    private void showBalanceFromDump(SparseArray<String[]> dump, byte[] uid) {
        resetCachedBalance();
        if (dump == null) {
            statusText.setText("La tarjeta se retiró durante la lectura.");
            return;
        }
        String[] sector = dump.get(BALANCE_SECTOR);
        if (sector == null || sector.length <= BALANCE_BLOCK_B) {
            statusText.setText("El sector 4 no se pudo leer con las keys seleccionadas.");
            return;
        }

        String hexA = sector[BALANCE_BLOCK_A];
        String hexB = sector[BALANCE_BLOCK_B];
        if (!Common.isValueBlock(hexA) || !Common.isValueBlock(hexB)) {
            statusText.setText("El perfil no coincide: los bloques 16 y 17 no son Value Blocks válidos.");
            return;
        }

        try {
            byte[] blockA = hexToBytes(hexA);
            byte[] blockB = hexToBytes(hexB);
            int valueA = decodeValueBlock(blockA);
            int valueB = decodeValueBlock(blockB);
            if (valueA < 0 || valueB < 0) {
                statusText.setText("El perfil de saldo no admite valores negativos.");
                return;
            }
            if (valueA != valueB) {
                statusText.setText("Las dos copias del saldo no coinciden. Usa Modo avanzado.");
                technicalText.setText("Bloque 16: " + valueA + " · Bloque 17: " + valueB);
                balancePanel.setVisibility(View.VISIBLE);
                editButton.setEnabled(false);
                return;
            }

            displayedCents = valueA;
            originalBlockA = blockA;
            originalBlockB = blockB;
            lastReadUid = uid;
            balanceText.setText(formatEuros(displayedCents));
            technicalText.setText("Sector 4 · bloques 16 y 17 · Value Block verificado");
            balancePanel.setVisibility(View.VISIBLE);
            editButton.setEnabled(true);
            statusText.setText("Tarjeta leída correctamente");
        } catch (IllegalArgumentException ex) {
            statusText.setText("No se pudo interpretar el saldo del perfil de laboratorio.");
        }
    }

    private void resetCachedBalance() {
        originalBlockA = null;
        originalBlockB = null;
        lastReadUid = null;
        editButton.setEnabled(false);
    }

    private void showBalanceEditor() {
        if (originalBlockA == null || originalBlockB == null || lastReadUid == null) {
            Toast.makeText(this, "Lee primero la tarjeta.", Toast.LENGTH_LONG).show();
            return;
        }

        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(String.format(Locale.US, "%.2f", displayedCents / 100.0));
        int pad = Common.dpToPx(18);
        input.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(this)
                .setTitle("Nuevo saldo")
                .setMessage("Tarjeta de laboratorio: mantén la misma tarjeta junto al móvil durante la escritura.")
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Escribir", (dialog, which) -> {
                    try {
                        String normalized = input.getText().toString().trim().replace(',', '.');
                        double euros = Double.parseDouble(normalized);
                        long cents = Math.round(euros * 100.0);
                        if (euros < 0 || cents > Integer.MAX_VALUE) throw new NumberFormatException();
                        confirmAndWriteBalance((int) cents);
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Introduce un importe válido.", Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void confirmAndWriteBalance(int newCents) {
        new AlertDialog.Builder(this)
                .setTitle("Confirmar escritura")
                .setMessage("Cambiar " + formatEuros(displayedCents) + " por "
                        + formatEuros(newCents) + " en los dos Value Blocks del perfil de laboratorio?")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Confirmar", (dialog, which) -> writeBalanceToLabTag(newCents))
                .show();
    }

    private void writeBalanceToLabTag(int newCents) {
        final byte[] beforeA = originalBlockA.clone();
        final byte[] beforeB = originalBlockB.clone();
        final byte[] expectedA = makeValueBlock(newCents, beforeA[12]);
        final byte[] expectedB = makeValueBlock(newCents, beforeB[12]);
        final byte[] expectedUid = lastReadUid.clone();

        statusText.setText("Escribiendo saldo… no retires la tarjeta.");
        editButton.setEnabled(false);

        new Thread(() -> {
            MCReader reader = Common.checkForTagAndCreateReader(this);
            if (reader == null) {
                handler.post(() -> writeFailed("No se detecta la tarjeta. Acércala y vuelve a leerla."));
                return;
            }

            byte[] currentUid = Common.getUID();
            if (currentUid == null || !Arrays.equals(expectedUid, currentUid)) {
                reader.close();
                handler.post(() -> writeFailed("La tarjeta detectada no es la misma que se leyó."));
                return;
            }

            SparseArray<byte[][]> keyMap = Common.getKeyMap();
            byte[][] keys = keyMap == null ? null : keyMap.get(BALANCE_SECTOR);
            if (keys == null) {
                reader.close();
                handler.post(() -> writeFailed("No están disponibles las keys del sector 4."));
                return;
            }

            int resultA = writeWithMappedKeys(reader, BALANCE_SECTOR, BALANCE_BLOCK_A, expectedA, keys);
            if (resultA != 0) {
                reader.close();
                handler.post(() -> writeFailed("No se pudo escribir el bloque 16 (código " + resultA + ")."));
                return;
            }

            int resultB = writeWithMappedKeys(reader, BALANCE_SECTOR, BALANCE_BLOCK_B, expectedB, keys);
            if (resultB != 0) {
                // Best-effort rollback of block 16 to avoid leaving duplicate copies inconsistent.
                writeWithMappedKeys(reader, BALANCE_SECTOR, BALANCE_BLOCK_A, beforeA, keys);
                reader.close();
                handler.post(() -> writeFailed("No se pudo escribir el bloque 17. Se intentó restaurar el bloque 16."));
                return;
            }

            SparseArray<String[]> verifyDump = reader.readAsMuchAsPossible(keyMap);
            reader.close();

            boolean verified = verifyValueBlocks(verifyDump, expectedA, expectedB, newCents);
            if (!verified) {
                handler.post(() -> writeFailed("La escritura terminó, pero la verificación no coincide. Usa Modo avanzado antes de continuar."));
                return;
            }

            handler.post(() -> {
                displayedCents = newCents;
                originalBlockA = expectedA;
                originalBlockB = expectedB;
                balanceText.setText(formatEuros(displayedCents));
                technicalText.setText("Sector 4 · bloques 16 y 17 · escritura verificada");
                statusText.setText("Saldo actualizado y verificado correctamente");
                editButton.setEnabled(true);
            });
        }).start();
    }

    private void writeFailed(String message) {
        statusText.setText(message);
        editButton.setEnabled(true);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /** Returns 0 on success, otherwise the most useful MCReader write error code. */
    private int writeWithMappedKeys(MCReader reader, int sector, int block,
                                    byte[] data, byte[][] keys) {
        int lastResult = 4; // authentication failed / no usable key
        if (keys.length > 1 && keys[1] != null) {
            lastResult = reader.writeBlock(sector, block, data, keys[1], true);
            if (lastResult == 0) return 0;
        }
        if (keys.length > 0 && keys[0] != null) {
            lastResult = reader.writeBlock(sector, block, data, keys[0], false);
            if (lastResult == 0) return 0;
        }
        return lastResult;
    }

    private boolean verifyValueBlocks(SparseArray<String[]> dump,
                                      byte[] expectedA, byte[] expectedB, int cents) {
        if (dump == null) return false;
        String[] sector = dump.get(BALANCE_SECTOR);
        if (sector == null || sector.length <= BALANCE_BLOCK_B) return false;
        try {
            if (!Common.isValueBlock(sector[BALANCE_BLOCK_A])
                    || !Common.isValueBlock(sector[BALANCE_BLOCK_B])) return false;
            byte[] actualA = hexToBytes(sector[BALANCE_BLOCK_A]);
            byte[] actualB = hexToBytes(sector[BALANCE_BLOCK_B]);
            return Arrays.equals(actualA, expectedA)
                    && Arrays.equals(actualB, expectedB)
                    && decodeValueBlock(actualA) == cents
                    && decodeValueBlock(actualB) == cents;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int decodeValueBlock(byte[] block) {
        if (block == null || block.length != 16) {
            throw new IllegalArgumentException("Expected 16-byte Value Block");
        }
        return (block[0] & 0xFF)
                | ((block[1] & 0xFF) << 8)
                | ((block[2] & 0xFF) << 16)
                | ((block[3] & 0xFF) << 24);
    }

    private static byte[] makeValueBlock(int value, byte address) {
        byte[] out = new byte[16];
        out[0] = (byte) (value & 0xFF);
        out[1] = (byte) ((value >>> 8) & 0xFF);
        out[2] = (byte) ((value >>> 16) & 0xFF);
        out[3] = (byte) ((value >>> 24) & 0xFF);
        out[4] = (byte) ~out[0];
        out[5] = (byte) ~out[1];
        out[6] = (byte) ~out[2];
        out[7] = (byte) ~out[3];
        out[8] = out[0];
        out[9] = out[1];
        out[10] = out[2];
        out[11] = out[3];
        out[12] = address;
        out[13] = (byte) ~address;
        out[14] = address;
        out[15] = (byte) ~address;
        return out;
    }

    private static byte[] hexToBytes(String value) {
        String hex = value == null ? "" : value.replaceAll("[^0-9A-Fa-f]", "");
        if (hex.length() != 32) {
            throw new IllegalArgumentException("Expected one 16-byte block");
        }
        byte[] out = new byte[16];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String formatEuros(long cents) {
        return String.format(Locale.getDefault(), "%.2f €", cents / 100.0);
    }
}
