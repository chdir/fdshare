package net.sf.fdshare;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity implements DialogInterface.OnClickListener, DialogInterface.OnCancelListener {
    private AlertDialog dialog;
    private EditText txt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(net.sf.mymodule.example.R.layout.activity_main);
    }

    @Override
    protected void onStart() {
        super.onStart();

        txt = new EditText(this);
        txt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        txt.setHint("absolute path without quotes");
        txt.setSingleLine();

        dialog = new AlertDialog.Builder(this)
                .setMessage("Enter file path")
                .setPositiveButton("Open", this)
                .setNegativeButton("Exit", this)
                .setOnCancelListener(this)
                .setView(txt)
                .show();
    }

    @Override
    protected void onStop() {
        dialog.dismiss();
        dialog = null;
        txt = null;

        super.onStop();
    }

    @Override
    public void onClick(DialogInterface dialogInterface, int i) {
        switch (i) {
            case DialogInterface.BUTTON_POSITIVE:
                final Uri uri = Uri.parse("content://" + RootFileProvider.AUTHORITY + txt.getText().toString());
                final Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.setData(uri);

                List<ResolveInfo> resInfoList = getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);

                if (resInfoList.isEmpty()) {
                    Toast.makeText(this, "No activities for editing found", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }

                for (ResolveInfo resolveInfo : resInfoList) {
                    String packageName = resolveInfo.activityInfo.packageName;
                    grantUriPermission(packageName, uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }

                startActivity(Intent.createChooser(intent, "Edit file with"));
                break;
            case DialogInterface.BUTTON_NEGATIVE:
                dialogInterface.dismiss();
                finish();
                break;
        }
    }

    @Override
    public void onCancel(DialogInterface dialogInterface) {
        finish();
    }
}
