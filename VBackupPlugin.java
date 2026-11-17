package com.vplayer.app;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/* a backup folder chosen by the person: one file with the app's notes (cuts, titles, posts, thumbnails, alarms) and the post pictures.
   The folder is outside the app, so it stays when the app is removed. Videos and songs are never copied. */
@CapacitorPlugin(name = "VBackup")
public class VBackupPlugin extends Plugin {
  private static final String PREF = "vp_backup";
  private static final String FILE = "vplayer-backup.json"; /* the first one-file backup, still read */
  private static final String OLDDIR = "old";
  private static final String PICS = "pics";

  private SharedPreferences prefs() {
    return getContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
  }

  private Uri tree() {
    String s = prefs().getString("tree", "");
    return s.length() == 0 ? null : Uri.parse(s);
  }

  private static String nameOf(Uri t) {
    try {
      String id = DocumentsContract.getTreeDocumentId(t);
      int i = Math.max(id.lastIndexOf('/'), id.lastIndexOf(':'));
      String n = i >= 0 ? id.substring(i + 1) : id;
      return n.length() == 0 ? id : n;
    } catch (Throwable e) {
      return "";
    }
  }

  /* [documentId, name, size, modified, mime] of everything inside one folder */
  private List<String[]> kids(Uri t, String parentId) {
    List<String[]> out = new ArrayList<String[]>();
    Cursor c = null;
    try {
      Uri cu = DocumentsContract.buildChildDocumentsUriUsingTree(t, parentId);
      c =
        getContext()
          .getContentResolver()
          .query(
            cu,
            new String[] {
              DocumentsContract.Document.COLUMN_DOCUMENT_ID,
              DocumentsContract.Document.COLUMN_DISPLAY_NAME,
              DocumentsContract.Document.COLUMN_SIZE,
              DocumentsContract.Document.COLUMN_LAST_MODIFIED,
              DocumentsContract.Document.COLUMN_MIME_TYPE
            },
            null,
            null,
            null
          );
      while (c != null && c.moveToNext()) {
        out.add(new String[] {c.getString(0), c.getString(1), String.valueOf(c.getLong(2)), String.valueOf(c.getLong(3)), c.getString(4)});
      }
    } catch (Throwable e) {
    } finally {
      try {
        if (c != null) c.close();
      } catch (Throwable e) {
      }
    }
    return out;
  }

  private static String[] named(List<String[]> l, String name) {
    for (String[] k : l) if (name.equals(k[1])) return k;
    return null;
  }

  private static String mimeOf(String n) {
    String s = n.toLowerCase();
    if (s.endsWith(".png")) return "image/png";
    if (s.endsWith(".webp")) return "image/webp";
    if (s.endsWith(".gif")) return "image/gif";
    if (s.endsWith(".json")) return "application/json";
    return "image/jpeg";
  }

  private static void pipe(InputStream in, OutputStream out) throws Exception {
    byte[] b = new byte[64 * 1024];
    int n;
    while ((n = in.read(b)) > 0) out.write(b, 0, n);
  }

  private static byte[] readAll(InputStream in) throws Exception {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    pipe(in, o);
    return o.toByteArray();
  }

  /* the person picks the folder once; the app keeps the right to use it */
  @PluginMethod
  public void choose(PluginCall call) {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    i.addFlags(
      Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    );
    startActivityForResult(call, i, "chooseDone");
  }

  @ActivityCallback
  private void chooseDone(PluginCall call, ActivityResult r) {
    if (call == null) return;
    if (r == null || r.getResultCode() != Activity.RESULT_OK || r.getData() == null || r.getData().getData() == null) {
      call.reject("cancelled");
      return;
    }
    Uri u = r.getData().getData();
    try {
      getContext().getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    } catch (Throwable e) {
    }
    prefs().edit().putString("tree", u.toString()).apply();
    info(call);
  }

  private static boolean isPart(String n) {
    return n != null && n.startsWith("vp-") && n.endsWith(".json");
  }

  /* the chosen folder and what is inside it: one file for each kind of thing */
  @PluginMethod
  public void info(PluginCall call) {
    JSObject r = new JSObject();
    Uri t = tree();
    r.put("folder", t == null ? "" : nameOf(t));
    JSArray files = new JSArray();
    boolean legacy = false;
    if (t != null) {
      try {
        for (String[] k : kids(t, DocumentsContract.getTreeDocumentId(t))) {
          if (isPart(k[1])) {
            JSObject o = new JSObject();
            o.put("n", k[1].substring(3, k[1].length() - 5));
            o.put("when", Long.parseLong(k[3]));
            o.put("size", Long.parseLong(k[2]));
            files.put(o);
          } else if (FILE.equals(k[1])) legacy = true;
        }
      } catch (Throwable e) {
      }
    }
    r.put("files", files);
    r.put("legacy", legacy);
    r.put("last", prefs().getLong("last", 0L));
    call.resolve(r);
  }

  @PluginMethod
  public void forget(PluginCall call) {
    prefs().edit().remove("tree").apply();
    call.resolve();
  }

  private void writeBytes(ContentResolver cr, Uri t, Uri parent, List<String[]> have, String name, byte[] data) throws Exception {
    String[] h = named(have, name);
    Uri u = h != null ? DocumentsContract.buildDocumentUriUsingTree(t, h[0]) : DocumentsContract.createDocument(cr, parent, mimeOf(name), name);
    if (u == null) throw new RuntimeException("cannot write");
    OutputStream os = cr.openOutputStream(u, "wt");
    os.write(data);
    os.close();
  }

  /* parts: { name: text }. Each one becomes its own file; the one that was there before is kept in the "old" folder */
  @PluginMethod
  public void save(final PluginCall call) {
    final JSObject parts = call.getObject("parts");
    final Uri t = tree();
    if (t == null) {
      call.reject("no folder");
      return;
    }
    if (parts == null || parts.length() == 0) {
      call.reject("nothing to save");
      return;
    }
    new Thread(
      new Runnable() {
        @Override
        public void run() {
          try {
            Context c = getContext();
            ContentResolver cr = c.getContentResolver();
            String rootId = DocumentsContract.getTreeDocumentId(t);
            Uri root = DocumentsContract.buildDocumentUriUsingTree(t, rootId);
            List<String[]> top = kids(t, rootId);
            List<String[]> oldHave = null;
            Uri oldDoc = null;
            Iterator<String> it = parts.keys();
            while (it.hasNext()) {
              String key = it.next();
              String name = "vp-" + key + ".json";
              byte[] data = parts.getString(key).getBytes("UTF-8");
              String[] cur = named(top, name);
              if (cur != null) {
                Uri cu = DocumentsContract.buildDocumentUriUsingTree(t, cur[0]);
                InputStream in = cr.openInputStream(cu);
                byte[] old = readAll(in);
                in.close();
                if (java.util.Arrays.equals(old, data)) continue;
                try {
                  if (oldDoc == null) {
                    String[] od = named(top, OLDDIR);
                    String oid;
                    if (od != null) oid = od[0];
                    else {
                      Uri nu = DocumentsContract.createDocument(cr, root, DocumentsContract.Document.MIME_TYPE_DIR, OLDDIR);
                      oid = nu == null ? null : DocumentsContract.getDocumentId(nu);
                    }
                    if (oid != null) {
                      oldDoc = DocumentsContract.buildDocumentUriUsingTree(t, oid);
                      oldHave = kids(t, oid);
                    }
                  }
                  if (oldDoc != null) writeBytes(cr, t, oldDoc, oldHave, name, old);
                } catch (Throwable e) {
                }
              }
              writeBytes(cr, t, root, top, name, data);
            }
            /* the post pictures */
            File[] mine = VPostPlugin.dir(c).listFiles();
            int copied = 0;
            if (mine != null && mine.length > 0) {
              String[] pk = named(top, PICS);
              String picsId;
              if (pk != null) picsId = pk[0];
              else {
                Uri pu = DocumentsContract.createDocument(cr, root, DocumentsContract.Document.MIME_TYPE_DIR, PICS);
                picsId = pu == null ? null : DocumentsContract.getDocumentId(pu);
              }
              if (picsId != null) {
                Uri pdoc = DocumentsContract.buildDocumentUriUsingTree(t, picsId);
                List<String[]> have = kids(t, picsId);
                for (File f : mine) {
                  if (!f.isFile()) continue;
                  String[] h = named(have, f.getName());
                  if (h != null && Long.parseLong(h[2]) == f.length()) continue;
                  Uri fu = h != null ? DocumentsContract.buildDocumentUriUsingTree(t, h[0]) : DocumentsContract.createDocument(cr, pdoc, mimeOf(f.getName()), f.getName());
                  if (fu == null) continue;
                  InputStream in = new FileInputStream(f);
                  OutputStream out = cr.openOutputStream(fu, "wt");
                  pipe(in, out);
                  in.close();
                  out.close();
                  copied++;
                }
              }
            }
            prefs().edit().putLong("last", System.currentTimeMillis()).apply();
            JSObject r = new JSObject();
            r.put("pics", copied);
            call.resolve(r);
          } catch (Throwable e) {
            call.reject("could not write the backup");
          }
        }
      }
    ).start();
  }

  /* names: the parts wanted. Gives back their text (the first one-file backup comes back as "legacy"); the post pictures are put back when "posts" is wanted */
  @PluginMethod
  public void load(final PluginCall call) {
    final Uri t = tree();
    final JSArray names = call.getArray("names");
    if (t == null) {
      call.reject("no folder");
      return;
    }
    new Thread(
      new Runnable() {
        @Override
        public void run() {
          try {
            Context c = getContext();
            ContentResolver cr = c.getContentResolver();
            String rootId = DocumentsContract.getTreeDocumentId(t);
            List<String[]> top = kids(t, rootId);
            JSObject parts = new JSObject();
            boolean wantPics = false;
            for (int i = 0; names != null && i < names.length(); i++) {
              String key = names.getString(i);
              if ("posts".equals(key)) wantPics = true;
              String fname = "legacy".equals(key) ? FILE : "vp-" + key + ".json";
              String[] cur = named(top, fname);
              if (cur == null) continue;
              InputStream in = cr.openInputStream(DocumentsContract.buildDocumentUriUsingTree(t, cur[0]));
              parts.put(key, new String(readAll(in), "UTF-8"));
              in.close();
            }
            int n = 0;
            String[] pk = named(top, PICS);
            if ((wantPics || parts.has("legacy")) && pk != null) {
              File dir = VPostPlugin.dir(c);
              for (String[] k : kids(t, pk[0])) {
                File f = new File(dir, k[1]);
                if (f.exists() && f.length() == Long.parseLong(k[2])) continue;
                InputStream pi = cr.openInputStream(DocumentsContract.buildDocumentUriUsingTree(t, k[0]));
                FileOutputStream po = new FileOutputStream(f);
                pipe(pi, po);
                pi.close();
                po.close();
                n++;
              }
            }
            JSObject r = new JSObject();
            r.put("parts", parts);
            r.put("pics", n);
            call.resolve(r);
          } catch (Throwable e) {
            call.reject("could not read the backup");
          }
        }
      }
    ).start();
  }
}
