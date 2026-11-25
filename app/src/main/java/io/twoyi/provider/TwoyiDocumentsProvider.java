/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.provider;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import io.twoyi.R;
import io.twoyi.utils.RomManager;

/**
 * @author weishu
 * @date 2022/2/16.
 */
public class TwoyiDocumentsProvider extends DocumentsProvider {

    private static final String ALL_MIME_TYPES = "*/*";

    private static final String DEFAULT_ROOT_ID = "0";

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE
    };

    @Override
    public Cursor queryRoots(String[] projection) {

        File root = getRootDir();
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        try {
            checkedSharedRoot();
        } catch (IOException ignored) {
            return result;
        }
        final MatrixCursor.RowBuilder row = result.newRow();
        for (String column : result.getColumnNames()) {
            switch (column) {
                case Root.COLUMN_ROOT_ID: row.add(DEFAULT_ROOT_ID); break;
                case Root.COLUMN_DOCUMENT_ID: row.add(getDocId(root)); break;
                case Root.COLUMN_SUMMARY: row.add(getRootSummary()); break;
                case Root.COLUMN_FLAGS:
                    row.add(Root.FLAG_SUPPORTS_CREATE | Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_IS_CHILD);
                    break;
                case Root.COLUMN_TITLE: row.add(getContext().getString(R.string.app_name)); break;
                case Root.COLUMN_MIME_TYPES: row.add(ALL_MIME_TYPES); break;
                case Root.COLUMN_AVAILABLE_BYTES: row.add(root.getFreeSpace()); break;
                case Root.COLUMN_ICON: row.add(R.mipmap.ic_launcher); break;
                default: row.add(null);
            }
        }

        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        includeFile(result, documentId, getFileById(documentId));
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        final File parent = getFileById(parentDocumentId);
        if (!parent.isDirectory()) throw new FileNotFoundException("Not a shared directory");
        File[] files = parent.listFiles();
        if (files != null) {
            for (File file : files) {
                try {
                    String documentId = getDocId(file);
                    includeFile(result, documentId, getFileById(documentId));
                } catch (FileNotFoundException ignored) {
                    // The entry disappeared or resolves outside the shared directory.
                }
            }
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, @Nullable CancellationSignal signal) throws FileNotFoundException {
        if (signal != null) signal.throwIfCanceled();
        final File file = getFileById(documentId);
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new FileNotFoundException("Only regular shared files can be opened");
        }
        final int accessMode = ParcelFileDescriptor.parseMode(mode);
        return ParcelFileDescriptor.open(file, accessMode);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        File parent = getFileById(parentDocumentId);
        if (!parent.isDirectory() || !validDisplayName(displayName)) {
            throw new FileNotFoundException("Invalid document name or parent");
        }
        File newFile = new File(parent, displayName);
        int noConflictId = 2;
        while (newFile.exists()) {
            newFile = new File(parent, displayName + " (" + noConflictId++ + ")");
        }
        try {
            boolean succeeded;
            if (Document.MIME_TYPE_DIR.equals(mimeType)) {
                succeeded = newFile.mkdir();
            } else {
                succeeded = newFile.createNewFile();
            }
            if (!succeeded) {
                throw new FileNotFoundException("Failed to create document: " + newFile.getPath());
            }
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to create document:" + newFile.getPath());
        }
        return newFile.getPath();
    }

    private static boolean validDisplayName(String name) {
        return name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..")
                && name.indexOf('/') < 0 && name.indexOf('\0') < 0;
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        File source = getFileById(documentId);
        if (isSharedRoot(source) || !validDisplayName(displayName)) {
            throw new FileNotFoundException("Invalid shared document rename");
        }
        File destination = new File(source.getParentFile(), displayName);
        if (destination.equals(source)) return getDocId(source);
        try {
            java.nio.file.Files.move(source.toPath(), destination.toPath());
            return getDocId(destination);
        } catch (IOException e) {
            FileNotFoundException failure = new FileNotFoundException("Unable to rename shared document");
            failure.initCause(e);
            throw failure;
        }
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File file = getFileById(documentId);
        if (isSharedRoot(file) || !file.delete()) {
            throw new FileNotFoundException("Failed to delete document:" + documentId);
        }
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        File file = getFileById(documentId);
        return getMimeType(file);
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        if (parentDocumentId == null || documentId == null) {
            return false;
        }
        try {
            java.nio.file.Path parent = getFileById(parentDocumentId).getCanonicalFile().toPath();
            java.nio.file.Path child = getFileById(documentId).getCanonicalFile().toPath();
            return !child.equals(parent) && child.startsWith(parent);
        } catch (IOException e) {
            return false;
        }
    }

    private static String getDocId(File file) {
        return file.getAbsolutePath();
    }

    private File getFileById(String docId) throws FileNotFoundException {
        if (docId != null && new File(docId).isAbsolute()) {
            try {
                Path root = checkedSharedRoot();
                File file = new File(docId).getCanonicalFile();
                if (file.toPath().startsWith(root) && file.exists()) {
                    return file;
                }
            } catch (IOException ignored) {
                // Report an unresolvable document through the SAF API.
            }
        }
        throw new FileNotFoundException("Document unavailable in the shared directory");
    }

    private static String getMimeType(File file) {
        if (file.isDirectory()) {
            return Document.MIME_TYPE_DIR;
        } else {
            final String name = file.getName();
            final int lastDot = name.lastIndexOf('.');
            if (lastDot >= 0) {
                final String extension = name.substring(lastDot + 1).toLowerCase();
                final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                if (mime != null) return mime;
            }
            return "application/octet-stream";
        }
    }

    private File getRootDir() {
        return RomManager.getRomSdcardDir(getContext());
    }

    private Path checkedSharedRoot() throws IOException {
        Path shared = getRootDir().toPath();
        if (!Files.isDirectory(shared.getParent(), LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(shared, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileNotFoundException("Shared directory unavailable");
        }
        Path dataDirectory = getContext().getDataDir().toPath();
        Path expected = dataDirectory.toRealPath().resolve(dataDirectory.relativize(shared));
        Path resolved = shared.toRealPath();
        if (!resolved.equals(expected)) {
            throw new FileNotFoundException("Shared directory no longer matches the guest path");
        }
        return resolved;
    }

    private boolean isSharedRoot(File file) throws FileNotFoundException {
        try {
            return file.getCanonicalFile().toPath().equals(checkedSharedRoot());
        } catch (IOException e) {
            throw new FileNotFoundException("Shared directory unavailable");
        }
    }

    private String getRootSummary() {
        return getContext().getResources().getString(R.string.sdcard_of_twoyi);
    }

    private void includeFile(MatrixCursor result, String docId, File file)
            throws FileNotFoundException {
        boolean isRoot = isSharedRoot(file);
        final MatrixCursor.RowBuilder row = result.newRow();
        for (String column : result.getColumnNames()) {
            switch (column) {
                case Document.COLUMN_DOCUMENT_ID: row.add(docId); break;
                case Document.COLUMN_DISPLAY_NAME: row.add(isRoot ? getRootSummary() : file.getName()); break;
                case Document.COLUMN_SIZE: row.add(file.length()); break;
                case Document.COLUMN_MIME_TYPE: row.add(getMimeType(file)); break;
                case Document.COLUMN_LAST_MODIFIED: row.add(file.lastModified()); break;
                case Document.COLUMN_FLAGS: row.add(getDocumentFlags(file, isRoot)); break;
                case Document.COLUMN_ICON: row.add(R.mipmap.ic_launcher); break;
                default: row.add(null);
            }
        }
    }

    private static int getDocumentFlags(File file, boolean isRoot) {
        int flags = 0;
        if (file.isDirectory()) {
            if (file.canWrite()) flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
        } else if (file.canWrite()) {
            flags |= Document.FLAG_SUPPORTS_WRITE;
        }

        File parentFile = file.getParentFile();
        if (!isRoot && parentFile != null && parentFile.canWrite()) {
            flags |= Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME;
        }
        return flags;
    }
}
