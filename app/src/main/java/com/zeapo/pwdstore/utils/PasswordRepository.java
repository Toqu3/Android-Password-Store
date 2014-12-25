package com.zeapo.pwdstore.utils;

import android.util.Log;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static java.util.Collections.sort;

public class PasswordRepository {

    private static Repository repository;
    private static boolean initialized = false;

    protected PasswordRepository(){    }

    /**
     * Returns the git repository
     * @param localDir needed only on the creation
     * @return the git repository
     */
    public static Repository getRepository(File localDir) {
        if (repository == null) {
            setRepository(localDir);
        }
        return repository;
    }

    /**
     * Sets the repository to a new directory
     * @param localDir the git directory of the repository
     * @return the git repository
     */
    public static Repository setRepository(File localDir) {
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        try {
            repository = builder.setGitDir(localDir)
                    .readEnvironment()
                    .build();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }

        return repository;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static void setInitialized(boolean v) {
        initialized = v;
    }

    public static void createRepository(File localDir) throws Exception{
        localDir.delete();

        Git.init().setDirectory(localDir).call();
        getRepository(localDir);
    }

    // TODO add multiple remotes support for pull/push
    public static void addRemote(String name, String url, Boolean replace) {
        StoredConfig storedConfig = repository.getConfig();
        Set<String> remotes = storedConfig.getSubsections("remote");

        if (!remotes.contains(name)) {
            try {
                URIish uri = new URIish(url);
                RefSpec refSpec = new RefSpec("+refs/head/*:refs/remotes/" + name + "/*");

                RemoteConfig remoteConfig = new RemoteConfig(storedConfig, name);
                remoteConfig.addFetchRefSpec(refSpec);
                remoteConfig.addPushRefSpec(refSpec);
                remoteConfig.addURI(uri);
                remoteConfig.addPushURI(uri);

                remoteConfig.update(storedConfig);

                storedConfig.save();
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else if (replace) {
            try {
                URIish uri = new URIish(url);

                RemoteConfig remoteConfig = new RemoteConfig(storedConfig, name);
                // remove the first and eventually the only uri
                if (remoteConfig.getURIs().size() > 0) {
                    remoteConfig.removeURI(remoteConfig.getURIs().get(0));
                }
                if (remoteConfig.getPushURIs().size() > 0) {
                    remoteConfig.removePushURI(remoteConfig.getPushURIs().get(0));
                }

                remoteConfig.addURI(uri);
                remoteConfig.addPushURI(uri);

                remoteConfig.update(storedConfig);

                storedConfig.save();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static String getRemote(String name) {
        StoredConfig storedConfig = repository.getConfig();
        Set<String> remotes = storedConfig.getSubsections("remote");

        for (String remote : remotes) {
            try {
                if (remote.equals(name)) {
                    RemoteConfig remoteConfig = new RemoteConfig(storedConfig, remote);
                    return remoteConfig.getPushURIs().get(0).toString();
                }
            } catch (Exception e) {

            }
        }
        return "";
    }

    public static void closeRepository() {
        repository.close();
    }

    public static ArrayList<File> getFilesList(){
        return getFilesList(repository.getWorkTree());
    }

//    public static String getName()

    /**
     * Gets the password items in the root directory
     * @return a list of passwords in the root direcotyr
     */
    public static ArrayList<PasswordItem> getPasswords() {
        return getPasswords(repository.getWorkTree());
    }

    public static File getWorkTree() {
        return repository.getWorkTree();
    }

    /**
     * Gets a file from the working tree
     * @param name the relative path of the file
     * @return the file in the repository
     */
    public static File getFile(String name) {
        return new File(repository.getWorkTree() + "/" + name);
    }

    /**
     * Gets the .gpg files in a directory
     * @param path the directory path
     * @return the list of gpg files in that directory
     */
    public static ArrayList<File> getFilesList(File path){
        if (!path.exists()) return new ArrayList<File>();

        Log.d("REPO", "current path: " + path.getPath());
        ArrayList<File> files = new ArrayList<File>(Arrays.asList(path.listFiles((FileFilter) FileFilterUtils.directoryFileFilter())));
        files.addAll( new ArrayList<File>((List<File>)FileUtils.listFiles(path, new String[] {"gpg"}, false)));

        return new ArrayList<File>(files);
    }

    /**
     * Gets the passwords (PasswordItem) in a directory
     * @param path the directory path
     * @return a list of password items
     */
    public static ArrayList<PasswordItem> getPasswords(File path) {
        //We need to recover the passwords then parse the files
        ArrayList<File> passList = getFilesList(path);

        if (passList.size() == 0) return new ArrayList<PasswordItem>();

        ArrayList<PasswordItem> passwordList = new ArrayList<PasswordItem>();

        for (File file : passList) {
            if (file.isFile()) {
                passwordList.add(PasswordItem.newPassword(file.getName(), file));
            } else {
                // ignore .git directory
                if (file.getName().equals(".git"))
                    continue;
                passwordList.add(PasswordItem.newCategory(file.getName(), file));
            }
        }
        sort(passwordList);
        return passwordList;
    }
}
