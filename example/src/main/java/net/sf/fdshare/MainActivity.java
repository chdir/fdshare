package net.sf.fdshare;

import android.app.Activity;
import android.app.ActivityGroup;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.LabeledIntent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.List;

@SuppressWarnings("ALL")
public class MainActivity extends Activity implements DialogInterface.OnClickListener, DialogInterface.OnCancelListener {
    private IntentHandler handler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(net.sf.mymodule.example.R.layout.activity_main);

        handler = new IntentHandler(this, Intent.ACTION_VIEW, true);
    }

    @Override
    protected void onResume() {
        super.onResume();

        showDialog(0);
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        final EditText txt = new EditText(this);
        txt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        txt.setHint("absolute path without quotes");
        txt.setSingleLine();

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setMessage("Enter file path")
                .setPositiveButton("Open", this)
                .setNeutralButton("Open with", this)
                .setNegativeButton("Exit", this)
                .setOnCancelListener(this)
                .setView(txt)
                .create();

        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> showChooser(txt.getText().toString(), true));
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> showChooser(txt.getText().toString(), false));
            }
        });
        return dlg;
    }

    private void showChooser(String file, boolean fast) {
        try {
            file = new File(file).getCanonicalPath();
        } catch (IOException ignore) {
            // a proper implementation would use any means possible to resolve a path
            // and fail if unsuccessful, but this tiny showcase won't include using
            // root access to resolve otherwise inaccessible symlink
        }

        final Intent openIntent = handler.createIntentForFile(file, fast);

        if (openIntent == null)
            Toast.makeText(this, "Unable to open a file", Toast.LENGTH_LONG).show();
        else
            startActivity(openIntent);
    }

    @Override
    protected void onPause() {
        removeDialog(0);

        super.onPause();
    }

    @Override
    public void finish() {
        removeDialog(0);

        super.finish();
    }

    @Override
    public void onClick(DialogInterface dialogInterface, int i) {
        switch (i) {
            case DialogInterface.BUTTON_NEGATIVE:
                finish();
                dialogInterface.dismiss();
                break;
        }
    }

    @Override
    public void onCancel(DialogInterface dialogInterface) {
        finish();
    }
}
