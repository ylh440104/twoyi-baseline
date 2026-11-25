/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;

import io.twoyi.utils.RomManager;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DocumentProjectionTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String authority = context.getPackageName() + ".documents";

    @Before
    public void prepareSharedDirectory() throws Exception {
        Files.createDirectories(RomManager.getRomSdcardDir(context).toPath());
    }

    @Test
    public void rootsHonorRequestedColumnOrder() {
        String[] columns = {Root.COLUMN_TITLE, "unknown_column", Root.COLUMN_DOCUMENT_ID};
        try (Cursor rows = query(DocumentsContract.buildRootsUri(authority), columns)) {
            assertTrue(rows.moveToFirst());
            assertArrayEquals(columns, rows.getColumnNames());
            assertEquals(context.getString(R.string.app_name), rows.getString(0));
            assertTrue(rows.isNull(1));
            assertEquals(RomManager.getRomSdcardDir(context).getAbsolutePath(), rows.getString(2));
        }
    }

    @Test
    public void imageDocumentAndChildListingHonorProjection() throws Exception {
        File shared = RomManager.getRomSdcardDir(context);
        Files.createDirectories(shared.toPath());
        File directory = Files.createTempDirectory(shared.toPath(), "projection-").toFile();
        File image = new File(directory, "sample.png");
        File dangling = new File(directory, "unavailable.png");
        try {
            assertTrue(image.createNewFile());
            Files.createSymbolicLink(dangling.toPath(), new File("missing.png").toPath());
            Uri document = DocumentsContract.buildDocumentUri(authority, image.getAbsolutePath());
            Uri children = DocumentsContract.buildChildDocumentsUri(authority, directory.getAbsolutePath());
            String[] columns = {Document.COLUMN_FLAGS, Document.COLUMN_ICON, "unknown_column",
                    Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE};
            for (Uri uri : new Uri[]{document, children}) {
                try (Cursor rows = query(uri, columns)) {
                    assertArrayEquals(columns, rows.getColumnNames());
                    assertEquals(1, rows.getCount());
                    assertTrue(rows.moveToFirst());
                    assertEquals(0, rows.getInt(0) & Document.FLAG_SUPPORTS_THUMBNAIL);
                    assertTrue((rows.getInt(0) & Document.FLAG_SUPPORTS_WRITE) != 0);
                    assertEquals(R.mipmap.ic_launcher, rows.getInt(1));
                    assertTrue(rows.isNull(2));
                    assertEquals(image.getName(), rows.getString(3));
                    assertEquals("image/png", rows.getString(4));
                }
            }
            try (Cursor rows = query(document, null)) {
                assertTrue(rows.moveToFirst());
                assertEquals(image.getAbsolutePath(), rows.getString(rows.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)));
            }
            byte[] contents = {1, 2, 3};
            Files.write(image.toPath(), contents);
            try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(document, "r")) {
                assertNotNull(descriptor);
                assertEquals(contents.length, descriptor.getStatSize());
            }
            Uri directoryDocument = DocumentsContract.buildDocumentUri(authority, directory.getAbsolutePath());
            try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(directoryDocument, "r")) {
                fail("A directory should be listed through the provider, not opened as a file");
            } catch (FileNotFoundException expected) {
                // Directories remain accessible through queryChildDocuments.
            }
        } finally {
            Files.deleteIfExists(dangling.toPath());
            Files.deleteIfExists(image.toPath());
            Files.delete(directory.toPath());
        }
    }

    @Test
    public void sharedFileCanBeCreatedWrittenRenamedAndDeleted() throws Exception {
        File shared = RomManager.getRomSdcardDir(context);
        File directory = Files.createTempDirectory(shared.toPath(), "document-operations-").toFile();
        File original = new File(directory, "before rename.txt");
        File renamed = new File(directory, "after rename.txt");
        android.content.ContentResolver resolver = context.getContentResolver();
        Uri parent = DocumentsContract.buildDocumentUri(authority, directory.getAbsolutePath());
        byte[] expected = {10, 20, 30};
        try {
            Uri created = DocumentsContract.createDocument(resolver, parent, "text/plain", original.getName());
            assertNotNull("Provider did not create the file", created);
            assertEquals(original.getCanonicalPath(), new File(DocumentsContract.getDocumentId(created)).getCanonicalPath());
            try (java.io.OutputStream stream = resolver.openOutputStream(created, "w")) {
                assertNotNull("Provider did not open the new file for writing", stream);
                stream.write(expected);
            }
            Uri moved = DocumentsContract.renameDocument(resolver, created, renamed.getName());
            assertNotNull("Provider did not return the renamed document", moved);
            assertFalse("The original path still exists", Files.exists(original.toPath()));
            assertArrayEquals(expected, Files.readAllBytes(renamed.toPath()));
            try (Cursor rows = query(moved, new String[]{Document.COLUMN_DISPLAY_NAME})) {
                assertTrue(rows.moveToFirst());
                assertEquals(renamed.getName(), rows.getString(0));
            }
            assertTrue("Provider did not delete the renamed file", DocumentsContract.deleteDocument(resolver, moved));
            assertFalse("The renamed path still exists", Files.exists(renamed.toPath()));
        } finally {
            Files.deleteIfExists(original.toPath());
            Files.deleteIfExists(renamed.toPath());
            Files.delete(directory.toPath());
        }
    }

    private Cursor query(Uri uri, String[] projection) {
        Cursor rows = context.getContentResolver().query(uri, projection, null, null, null);
        assertNotNull(rows);
        return rows;
    }
}
