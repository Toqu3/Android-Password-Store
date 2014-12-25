package com.zeapo.pwdstore;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.widget.SearchView;
import android.support.v7.widget.Toolbar;
import android.text.InputType;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;

import com.zeapo.pwdstore.crypto.PgpHandler;
import com.zeapo.pwdstore.git.GitActivity;
import com.zeapo.pwdstore.git.GitAsyncTask;
import com.zeapo.pwdstore.utils.PasswordItem;
import com.zeapo.pwdstore.utils.PasswordRecyclerAdapter;
import com.zeapo.pwdstore.utils.PasswordRepository;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;


public class PasswordStore extends ActionBarActivity  {
    private static final String TAG = "PWDSTR";
    private Stack<Integer> scrollPositions;
    /** if we leave the activity to do something, do not add any other fragment */
    public boolean leftActivity = false;
    private File currentDir;
    private SharedPreferences settings;
    private Activity activity;
    private PasswordFragment plist;
    private Toolbar toolbar;

    // ugly, we are reload the list each time...
    private List<File> repositories;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pwdstore);
        scrollPositions = new Stack<Integer>();
        settings = PreferenceManager.getDefaultSharedPreferences(this.getApplicationContext());
        activity = this;

        toolbar = (Toolbar) findViewById(R.id.main_toolbar);

        // Set an OnMenuItemClickListener to handle menu item clicks
        toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                // Handle the menu item
                return true;
            }
        });

        loadRepositories();

    }

    @Override
    public void onResume(){
        super.onResume();

        // create the repository static variable in PasswordRepository
        PasswordRepository.getRepository(new File(getFilesDir() + settings.getString("current_git_repo", "store") + "/.git"));

        // re-check that there was no change with the repository state
        checkLocalRepository();
    }

    @Override
    public void onPause() {
        super.onPause();
        this.leftActivity = true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.pwdstore, menu);
        MenuItem searchItem = menu.findItem(R.id.action_search);
        SearchView searchView = (SearchView) MenuItemCompat.getActionView(searchItem);
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String s) {
                return true;
            }

            @Override
            public boolean onQueryTextChange(String s) {
                filterListAdapter(s);
                return true;
            }
        });

        // When using the support library, the setOnActionExpandListener() method is
        // static and accepts the MenuItem object as an argument
        MenuItemCompat.setOnActionExpandListener(searchItem, new MenuItemCompat.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                refreshListAdapter();
                return true;
            }

            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                return true;
            }
        });
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        Intent intent;
        Log.d("PASS", "Menu item " + id + " pressed");

        AlertDialog.Builder initBefore = new AlertDialog.Builder(this)
                .setMessage(this.getResources().getString(R.string.creation_dialog_text))
                .setPositiveButton(this.getResources().getString(R.string.dialog_ok), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                    }
                });

        switch (id) {
            case R.id.user_pref:
                try {
                    intent = new Intent(this, UserPreference.class);
                    startActivity(intent);
                } catch (Exception e) {
                    System.out.println("Exception caught :(");
                    e.printStackTrace();
                }
                this.leftActivity = true;
                return true;

            case R.id.menu_add_password:
                if (!PasswordRepository.isInitialized()) {
                    initBefore.show();
                    break;
                }

                createPassword(getCurrentFocus());
                break;

//            case R.id.menu_add_category:
//                break;

            case R.id.git_push:
                if (!PasswordRepository.isInitialized()) {
                    initBefore.show();
                    break;
                }

                intent = new Intent(this, GitActivity.class);
                intent.putExtra("Operation", GitActivity.REQUEST_PUSH);
                startActivityForResult(intent, GitActivity.REQUEST_PUSH);
                this.leftActivity = true;
                return true;

            case R.id.git_pull:
                if (!PasswordRepository.isInitialized()) {
                    initBefore.show();
                    break;
                }

                intent = new Intent(this, GitActivity.class);
                intent.putExtra("Operation", GitActivity.REQUEST_PULL);
                startActivityForResult(intent, GitActivity.REQUEST_PULL);
                this.leftActivity = true;
                return true;

            case R.id.new_repository:
                final EditText repoName = new EditText(this);
                repoName.setHint("Repository name");
                repoName.setWidth(LinearLayout.LayoutParams.MATCH_PARENT);
                repoName.setInputType(InputType.TYPE_CLASS_TEXT);

                new AlertDialog.Builder(this)
                        .setTitle(this.getResources().getString(R.string.new_repo_title))
                        .setMessage(this.getResources().getString(R.string.new_repo_message))
                        .setView(repoName)
                        .setPositiveButton(this.getResources().getString(R.string.dialog_ok), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int whichButton) {
                                String repositoryName = repoName.getText().toString();
                                String fileName = Normalizer.normalize(repositoryName.toLowerCase().replace(' ', '_'), Normalizer.Form.NFD);
                                File repoFile = new File(getFilesDir() + "/" + fileName);
                                try {
                                    FileUtils.forceMkdir(repoFile);
                                    FileUtils.writeStringToFile(new File(repoFile.getAbsolutePath() + "/.name"), repositoryName);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                    new AlertDialog.Builder(activity).
                                            setMessage(e.getMessage()).
                                            setPositiveButton(activity.getResources().getString(R.string.dialog_ok), new DialogInterface.OnClickListener() {
                                                @Override
                                                public void onClick(DialogInterface dialogInterface, int i) {

                                                }
                                            }).show();
                                    return;
                                }

                                PasswordRepository.setRepository(new File(repoFile.getAbsolutePath() + "/.git"));
                                settings.edit().putString("current_git_repo", repoFile.getName()).commit();
                                checkLocalRepository(true);
                            }
                        }).setNegativeButton(this.getResources().getString(R.string.dialog_cancel), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        // Do nothing.
                    }
                }).show();
                break;

            case R.id.refresh:
                updateListAdapter();
                return true;

            case android.R.id.home:
                Log.d("PASS", "Home pressed");
                this.onBackPressed();
                break;

            default:
                break;
        }

        return super.onOptionsItemSelected(item);
    }

    /*
     * for each repository get the .name file, read it.
     * if there is nothing inside, or there is no .name file, set the path as the name
     * of the repository
     */
    private void loadRepositories() {
        repositories = new ArrayList<File>(Arrays.asList(getFilesDir().listFiles((FileFilter) FileFilterUtils.directoryFileFilter())));
        String currentlySelected = settings.getString("current_git_repo", "store");
        int currentlySelectedIndex = 0;

        List<String> repositoriesNames = new ArrayList<>();
        int idx = 0;
        for (File repo : repositories) {
            File nameFile = new File(repo.getAbsolutePath() + "/.name");
            String name = "";

            if (nameFile.exists()) {
                try {
                    name = FileUtils.readFileToString(nameFile);
                } catch (Exception e) {
                    // do nothing
                }
            }

            if (name.isEmpty()) {
                name = repo.getName();
            }

            if (name.equals(currentlySelected)) {
                currentlySelectedIndex = idx;
            }
            idx++;
            repositoriesNames.add(name);
        }

        final ArrayAdapter<String> repoSpinnerAdapter = new ArrayAdapter<String>(getApplicationContext(), android.R.layout.simple_spinner_item, repositoriesNames);
        Spinner repoSpinner = (Spinner) toolbar.findViewById(R.id.repo_spinner);
        repoSpinner.setAdapter(repoSpinnerAdapter);
        repoSpinner.setSelection(currentlySelectedIndex);
        repoSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                String repoName = ((Spinner) findViewById(R.id.repo_spinner)).getSelectedItem().toString();
                File repoFile = repositories.get(i);
                PasswordRepository.setRepository(new File(repoFile.getAbsolutePath() + "/.git"));
                settings.edit().putString("current_git_repo", repoFile.getName()).commit();
                checkLocalRepository(true);
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });
    }

    public void getClone(View view){
        Intent intent = new Intent(this, GitActivity.class);
        intent.putExtra("Operation", GitActivity.REQUEST_CLONE);
        startActivityForResult(intent, GitActivity.REQUEST_CLONE);
    }

    private void createRepository() {
        final String keyId = settings.getString("openpgp_key_ids", "");

        File localDir = PasswordRepository.getWorkTree();
        localDir.mkdir();
        try {
            PasswordRepository.createRepository(localDir);

            // we take only the first key-id, we have to think about how to handle multiple keys, and why should we do that...
            // also, for compatibility use short-version of the key-id
            FileUtils.writeStringToFile(new File(localDir.getAbsolutePath() + "/.gpg-id"),
                    keyId.substring(keyId.length() - 8));

            Git git = new Git(PasswordRepository.getRepository(new File("")));
            GitAsyncTask tasks = new GitAsyncTask(this, false, false, CommitCommand.class);
            tasks.execute(
                    git.add().addFilepattern("."),
                    git.commit().setMessage(R.string.initialization_commit_text + keyId)
            );
        } catch (Exception e) {
            e.printStackTrace();
            localDir.delete();
            return;
        }
        checkLocalRepository();
    }

    public void initRepository(View view) {
        final String keyId = settings.getString("openpgp_key_ids", "");

        if (keyId.isEmpty())
            new AlertDialog.Builder(this)
                    .setMessage(this.getResources().getString(R.string.key_dialog_text))
                    .setPositiveButton(this.getResources().getString(R.string.dialog_positive), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            Intent intent = new Intent(activity, UserPreference.class);
                            startActivityForResult(intent, GitActivity.REQUEST_INIT);
                        }
                    })
                    .setNegativeButton(this.getResources().getString(R.string.dialog_negative), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            // do nothing :(
                        }
                    })
                    .show();

        else {
            new AlertDialog.Builder(this)
                    .setMessage(this.getResources().getString(R.string.connection_dialog_text))
                    .setPositiveButton("ssh-key", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            settings.edit().putString("git_remote_auth", "ssh-key").apply();
                            createRepository();
                        }
                    })
                    .setNegativeButton("username/password", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            settings.edit().putString("git_remote_auth", "username/password").apply();
                            createRepository();
                        }
                    })
                    .setCancelable(false)


                    .show();

        }
    }

    private void checkLocalRepository() {
        checkLocalRepository(false);
    }

    private void checkLocalRepository(boolean newRepo) {
        checkLocalRepository(PasswordRepository.getWorkTree(), newRepo);
    }

    private void checkLocalRepository(File localDir, boolean newRepo) {
        Log.d(TAG, "Check, dir: " + localDir.getAbsolutePath());
        FragmentManager fragmentManager = getFragmentManager();
        FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();

        int status = 0;

        if (localDir.exists()) {
            // if we are coming back from gpg do not anything
            if (this.leftActivity) {
                this.leftActivity = false;
                return;
            }

            File[] folders = localDir.listFiles();
            status = folders.length;

            // this means that the repository has been correctly cloned
            if ((new File(localDir.getAbsolutePath() + "/.gpg-id")).exists())
                status++;

            // ignore the .name from the count
            if ((new File(localDir.getAbsolutePath() + "/.name")).exists() && status > 0)
                status--;
        }

        // either the repo is empty or it was not correctly cloned
        switch (status) {
            case 0:
                if(!localDir.equals(PasswordRepository.getWorkTree()) && localDir.exists())
                    break;
                PasswordRepository.setInitialized(false);

                // if we still have the pass list (after deleting for instance) remove it
                if (fragmentManager.findFragmentByTag("PasswordsList") != null) {
                    fragmentManager.popBackStack();
                }

                ToCloneOrNot cloneFrag = new ToCloneOrNot();
                fragmentTransaction.replace(R.id.main_layout, cloneFrag, "ToCloneOrNot");
                fragmentTransaction.commit();
                break;
            default:
                if ((fragmentManager.findFragmentByTag("PasswordsList") != null) && newRepo) {
                    fragmentManager.popBackStack();
                }

                if (fragmentManager.findFragmentByTag("PasswordsList") == null) {

                    // clean things up
                    if (fragmentManager.findFragmentByTag("ToCloneOrNot") != null) {
                        fragmentManager.popBackStack();
                    }

                    PasswordRepository.setInitialized(true);
                    plist = new PasswordFragment();
                    Bundle args = new Bundle();
                    args.putString("Path", localDir.getAbsolutePath());

                    plist.setArguments(args);

                    fragmentTransaction.addToBackStack("passlist");

                    fragmentTransaction.replace(R.id.main_layout, plist, "PasswordsList");
                    fragmentTransaction.commit();
                }
        }
        this.leftActivity = false;
    }



    @Override
    public void onBackPressed() {
        if  ((null != plist) && plist.isNotEmpty()) {
            plist.popBack();
        } else {
            super.onBackPressed();
        }

        if (null != plist && !plist.isNotEmpty()) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
        }
    }

    public void decryptPassword(PasswordItem item) {
        try {
            this.leftActivity = true;

            Intent intent = new Intent(this, PgpHandler.class);
            intent.putExtra("PGP-ID", FileUtils.readFileToString(PasswordRepository.getFile("/.gpg-id")));
            intent.putExtra("NAME", item.toString());
            intent.putExtra("FILE_PATH", item.getFile().getAbsolutePath());
            intent.putExtra("Operation", "DECRYPT");
            startActivityForResult(intent, PgpHandler.REQUEST_CODE_DECRYPT_AND_VERIFY);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void createPassword(View v) {
        this.currentDir = getCurrentDir();
        Log.i("PWDSTR", "Adding file to : " + this.currentDir.getAbsolutePath());
        this.leftActivity = true;

        try {
            Intent intent = new Intent(this, PgpHandler.class);
            intent.putExtra("PGP-ID", FileUtils.readFileToString(PasswordRepository.getFile("/.gpg-id")));
            intent.putExtra("FILE_PATH", getCurrentDir().getAbsolutePath());
            intent.putExtra("Operation", "ENCRYPT");
            startActivityForResult(intent, PgpHandler.REQUEST_CODE_ENCRYPT);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void deletePassword(final PasswordRecyclerAdapter adapter, final int position) {
        final PasswordItem item = adapter.getValues().get(position);
        new AlertDialog.Builder(this).
                setMessage(this.getResources().getString(R.string.delete_dialog_text) +
                        item + "\"")
                .setPositiveButton(this.getResources().getString(R.string.dialog_yes), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        String path = item.getFile().getAbsolutePath();
                        item.getFile().delete();
                        adapter.remove(position);

                        setResult(RESULT_CANCELED);
                        Git git = new Git(PasswordRepository.getRepository(new File("")));
                        GitAsyncTask tasks = new GitAsyncTask(activity, false, true, CommitCommand.class);
                        System.out.println(tasks);
                        tasks.execute(
                                git.rm().addFilepattern(path.replace(PasswordRepository.getWorkTree() + "/", "")),
                                git.commit().setMessage("[ANDROID PwdStore] Remove " + item + " from store.")
                        );
                    }
                })
                .setNegativeButton(this.getResources().getString(R.string.dialog_no), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {

                    }
                })
                .show();
    }

    /**
     * clears adapter's content and updates it with a fresh list of passwords from the root
     */
    public void updateListAdapter() {
        if  ((null != plist)) {
            plist.updateAdapter();
        }
    }

    /**
     * Updates the adapter with the current view of passwords
     */
    public void refreshListAdapter() {
        if  ((null != plist)) {
            plist.refreshAdapter();
        }
    }

    public void filterListAdapter(String filter) {
        if  ((null != plist)) {
            plist.filterAdapter(filter);
        }
    }

    private File getCurrentDir() {
        if  ((null != plist)) {
            return plist.getCurrentDir();
        }
        return null;
    }

    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent data) {
        if (resultCode == RESULT_OK) {
            if (requestCode == GitActivity.REQUEST_CLONE)
                checkLocalRepository();

            switch (requestCode) {
                case PgpHandler.REQUEST_CODE_ENCRYPT :
                    Git git = new Git(PasswordRepository.getRepository(new File("")));
                    GitAsyncTask tasks = new GitAsyncTask(this, false, false, CommitCommand.class);
                    tasks.execute(
                            git.add().addFilepattern("."),
                            git.commit().setMessage(this.getResources().getString(R.string.add_commit_text) + data.getExtras().getString("NAME") + this.getResources().getString(R.string.from_store))
                    );
                    refreshListAdapter();
                    break;
                case GitActivity.REQUEST_INIT:
                    initRepository(getCurrentFocus());
                    break;
                case GitActivity.REQUEST_PULL:
                    updateListAdapter();
                    break;
            }

        }
    }
}
