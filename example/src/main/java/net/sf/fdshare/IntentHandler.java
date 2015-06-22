/*
 * Copyright © 2015 Alexander Rvachev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.sf.fdshare;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.LabeledIntent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;
import com.j256.simplemagic.ContentType;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This class can open files in 4 ways:
 * 1) Passing Uri of non-privileged ContentProvider to apps. This ensures, that compatible
 * apps will be able to access the contents, as long as this app can do it.
 * 2) Passing Uri of privileged RootFileProvider to apps. This ensures, that compatible
 * apps will be able to access the contents, as long as there is a root access.
 * 3) Passing file:// Uri to apps. They may be able to open them, as long as they have access.
 *
 * All of ways above are tried and combined by mixing together a handful of labeled Intents and doing batch
 * PackageManager queries to make sure, that every possible way is used. The content type of file is determined
 * beforehand to make sure, that every capable app is counted in.
 */
@SuppressLint("InlinedApi")
public class IntentHandler extends ContextWrapper {
    private final String intentAction;
    private final boolean hasRoot;

    public IntentHandler(@NonNull Context base, @NonNull String intentAction, boolean hasRoot) {
        super(base.getApplicationContext());

        this.intentAction = intentAction;
        this.hasRoot = hasRoot;
    }

    /**
     * Create a suitable ContentProvider Uri for file.
     * <p>
     * *You should canonicalize paths before passing to this method, if only to prevent symlink attacks*
     *
     * @param filePath canonical path to file, such as returned by {@link File#getCanonicalPath()}
     * @return created Uri, otherwise null if root access is required to create one, but not available
     */
    public @Nullable Uri getProviderUriForFile(@NonNull String filePath) {
        String ctxAuthority = getPackageName();

        if (canAccess(filePath)) {
            ctxAuthority += SimpleFilePorvider.AUTHORITY;
        } else if (hasRoot) {
            ctxAuthority += RootFileProvider.AUTHORITY;
        } else {
            // great, that we have sorted things out early...
            return null;
        }

        return Uri.parse("content://" + ctxAuthority + filePath);
    }

    /**
     * Create an Intent for performing an action on file. Created Intent may resolve to single Activity or chooser
     * dialog. It will behave as expected when passed to {@link #startActivity} and
     * {@link android.app.Activity#startActivityForResult}, but may point at some intermediate Activity to perform
     * additional processing and filtering.
     * <p>
     * *You should canonicalize paths before passing to this method, if only to prevent symlink attacks*
     *
     * @param filePath canonical path to file, such as returned by {@link File#getCanonicalPath()}
     * @param tryFastPath true, if a fast Intent resolution (e.g. when user simply clicks a file icon) is needed
     * @return created Intent, if any is available, null otherwise
     */
    public @Nullable Intent createIntentForFile(@NonNull String filePath, boolean tryFastPath) {
        final PackageManager pm = getPackageManager();
        final ContentResolver cr = getContentResolver();

        // 1) Pick, which ContentProvider to use (e.g. whether we need/support root)
        final Uri providerUri = getProviderUriForFile(filePath);
        // If we can not access the file, bail early. Real file manager would not likely come this far due to
        // directory permissions
        if (providerUri == null)
            return null;

        // 2) Try to canonicalize the Uri / file path, using chosen provider. If failed, bail out. Real app should show
        // this canonical path to user somewhere to ensure, that he knows, what he opens.
        final Uri canonicalUri = providerUri; // TODO

        // 3) Get MIME type (for clients, that handle only file://)
        final String[] mimeCandidates = providerUri == null
                ? new String[] {"*/*"}
                : cr.getStreamTypes(providerUri, "*/*");

        // 4) Generate 2 types of Uri: file:// and content://-based
        final Intent fileReferenceIntent = intentFor(Uri.parse("file://" + filePath));
        final Intent contentReferenceIntent = intentFor(canonicalUri);

        // 5) Get permission flags for, "access via provider Uri" scenario
        // We don't grant permanent permissions for writing. We also don't grant permanent permission for files,
        // known to have dynamic content.
        boolean osFs = filePath.startsWith("/proc/") || filePath.startsWith("/sys/") || filePath.startsWith("/dev/");

        int secFlags;
        switch (intentAction) {
            case Intent.ACTION_EDIT:
                secFlags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION;
                break;
            default:
                secFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;

                if (!osFs && Build.VERSION.SDK_INT >= 19)
                    secFlags |= Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
        }
        contentReferenceIntent.addFlags(secFlags);

        // 6) These flags will be used for all PackageManager queries. Filters are used to simplify out judgement,
        // as well as detect stuff like ResolverActivity, that does not belong here in the first place
        final int pmFlags = PackageManager.GET_RESOLVED_FILTER | (tryFastPath ? PackageManager.MATCH_DEFAULT_ONLY : 0);

        // 7) If we are acting upon something like user's tap on file icon, just pick whatever comes first (unless
        // it is a system Intent resolver, in which case we must offer user a better choice)
        if (tryFastPath) {
            ResolveInfo defaultApp; Intent luckyIntent;

            luckyIntent = contentReferenceIntent;
            defaultApp = pm.resolveActivity(luckyIntent, pmFlags);

            if (canAccess(filePath)) {
                if (isEmptyMatch(defaultApp)) {
                    luckyIntent = fileReferenceIntent;
                    defaultApp = pm.resolveActivity(luckyIntent, pmFlags);
                }

                if (isEmptyMatch(defaultApp)) {
                    for (String mime : mimeCandidates) {
                        fileReferenceIntent.setDataAndType(fileReferenceIntent.getData(), mime);

                        defaultApp = pm.resolveActivity(fileReferenceIntent, pmFlags);

                        if (defaultApp != null && defaultApp.filter != null)
                            break;
                    }
                }
            }

            if (!isEmptyMatch(defaultApp)) {
                luckyIntent.setClassName(defaultApp.activityInfo.applicationInfo.packageName, defaultApp.activityInfo.name);
                return luckyIntent;
            }
        }

        // 8) Let's do some PackageManager queries to get as much results as possible, and combine them together via
        // Intent chooser LabeledIntent support. In order to do so we will use specially prepared Intents with all
        // possible MIME types, added file extensions etc. to work around limitations of Intent resolution mechanism.
        // Those "reference" Intents aren't necessarily what will be sent to clients.
        final List<Intent> variousApproaches = new ArrayList<>();

        // Special intent, that tells content provider to give away no content type. Useful as placeholder and to
        // match otherwise unmatchable, weird intent filters.
        final Intent untypedContentIntent = new Intent(contentReferenceIntent)
                .setData(contentReferenceIntent.getData().buildUpon().appendQueryParameter("type", "null").build());
        final Intent[] extraIntents = makeMultipleIntents(contentReferenceIntent, mimeCandidates);

        variousApproaches.add(contentReferenceIntent);
        variousApproaches.add(untypedContentIntent);
        variousApproaches.addAll(Arrays.asList(extraIntents));

        if (canAccess(filePath)) {
            final Intent[] extraFileIntents = makeMultipleIntents(fileReferenceIntent, mimeCandidates);
            final Intent untypedFileIntent = new Intent(fileReferenceIntent).setData(Uri.parse("file://" + filePath));

            variousApproaches.add(untypedFileIntent);
            variousApproaches.addAll(Arrays.asList(extraFileIntents));
        }

        // 9) Put result of Intent resolution queries in Map, using component name as key to ensure, that no duplicates
        // exist. Additionally, ensure, that no duplicate labels will slip in the list (e.g. if some developer was
        // expecting, that only one of Activities will ever be matched and gave them same labels). The later may
        // reduce user's choice, but duplicates are worse and will surely be considered a fault on our side
        final Map<ComponentName, ResolveInfo> resolved = new HashMap<>();
        final Set<String> names = new HashSet<>();
        for (Intent approach:variousApproaches) {
            List<ResolveInfo> result = pm.queryIntentActivities(approach, pmFlags);
            for (ResolveInfo res:result) {
                final String visualId = res.activityInfo.applicationInfo.packageName + res.loadLabel(pm);

                if (!names.contains(visualId)) {
                    resolved.put(new ComponentName(res.activityInfo.packageName, res.activityInfo.name), res);
                    names.add(visualId);
                }
            }
        }

        final ArrayList<LabeledIntent> actvityFilters = new ArrayList<>(resolved.size());

        for (Map.Entry<ComponentName, ResolveInfo> resEntry:resolved.entrySet()) {
            final ResolveInfo info = resEntry.getValue();

            if (isEmptyMatch(info))
                continue;

            final Intent realIntent = new Intent(info.filter.hasDataScheme("content") ? contentReferenceIntent : fileReferenceIntent);

            realIntent.setComponent(resEntry.getKey());

            final String suppliedLabel = info.activityInfo.loadLabel(pm).toString();

            // Note, that we app icon is used here instead of Intent filter icon. Those icons are bad idea, and
            // may be highly misleading to users, when the choice pops up
            final LabeledIntent intent = new LabeledIntent(realIntent, info.activityInfo.packageName,
                    suppliedLabel, info.activityInfo.applicationInfo.icon);

            actvityFilters.add(intent);
        }

        switch (actvityFilters.size()) {
            case 0:
                return null;
            case 1:
                return new Intent(actvityFilters.get(0));
            default:
                return Intent.createChooser(actvityFilters.remove(actvityFilters.size() - 1), "Choose app")
                        .putExtra(Intent.EXTRA_INITIAL_INTENTS, actvityFilters.toArray(new Parcelable[actvityFilters.size()]))
                        .addFlags(secFlags);
        }
    }

    private Intent[] makeMultipleIntents(Intent untypedIntent, String[] possibleMimeTypes) {
        final Uri intentUri = untypedIntent.getData();

        final Intent[] extraIntents = new Intent[possibleMimeTypes.length];

        for (int i = 0; i < possibleMimeTypes.length; i ++) {
            Uri newUri;

            if ("content".equals(intentUri.getScheme())) {
                newUri = intentUri.buildUpon().appendQueryParameter("type", possibleMimeTypes[i]).build();
            } else {
                newUri = intentUri;

                // apply extra effort for files without extensions
                if (TextUtils.isEmpty(MimeTypeMap.getFileExtensionFromUrl(intentUri.toString()))) {
                    final ContentType libmagicType = ContentType.fromMimeType(possibleMimeTypes[i]);
                    if (libmagicType != null) {
                        final String[] possibleExtensions = libmagicType.getFileExtensions();

                        if (possibleExtensions != null && possibleExtensions.length != 0) {
                            newUri = newUri.buildUpon().path(newUri.getPath() + '.' + possibleExtensions[0]).build();
                        }
                    }
                }
            }

            extraIntents[i] = new Intent(untypedIntent)
                    .setDataAndType(newUri, possibleMimeTypes[i]);
        }
        return extraIntents;
    }

    private Intent intentFor(Uri uri) {
        //noinspection deprecation
        return new Intent(intentAction)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET)
                .setData(uri);
    }

    private boolean canAccess(String file) {
        switch (intentAction) {
            case Intent.ACTION_EDIT:
                return new File(file).canWrite();
            default:
                return new File(file).canRead();
        }
    }

    private static boolean isEmptyMatch(ResolveInfo info) {
        return info == null || info.filter == null || info.activityInfo.name.endsWith("ResolverActivity");
    }
}
