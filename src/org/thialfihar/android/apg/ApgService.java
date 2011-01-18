package org.thialfihar.android.apg;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.thialfihar.android.apg.provider.KeyRings;
import org.thialfihar.android.apg.provider.Keys;
import org.thialfihar.android.apg.provider.UserIds;

import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteQueryBuilder;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

public class ApgService extends Service {
    private final static String TAG = "ApgService";

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "bound");
        return mBinder;
    }

    /** error status */
    private enum error {
        ARGUMENTS_MISSING,
        APG_FAILURE,
        NO_MATCHING_SECRET_KEY
    }

    /** all arguments that can be passed by calling application */
    private enum arg {
        MSG, // message to encrypt or to decrypt
        SYM_KEY, // key for symmetric en/decryption
        PUBLIC_KEYS, // public keys for encryption
        ENCRYPTION_ALGO, // encryption algorithm
        HASH_ALGO, // hash algorithm
        ARMORED, // whether to armor output
        FORCE_V3_SIG, // whether to force v3 signature
        COMPRESSION, // what compression to use for encrypted output
        SIGNATURE_KEY, // key for signing
        PRIVATE_KEY_PASS, // passphrase for encrypted private key
    }

    /** all things that might be returned */
    private enum ret {
        ERRORS, // string array list with errors
        WARNINGS, // string array list with warnings
        ERROR, // numeric error
        RESULT, // en-/decrypted test
    }

    /** required arguments for each AIDL function */
    private static final HashMap<String, Set<arg>> FUNCTIONS_REQUIRED_ARGS = new HashMap<String, Set<arg>>();
    static {
        HashSet<arg> args = new HashSet<arg>();
        args.add(arg.SYM_KEY);
        args.add(arg.MSG);
        FUNCTIONS_REQUIRED_ARGS.put("encrypt_with_passphrase", args);

        args = new HashSet<arg>();
        args.add(arg.PUBLIC_KEYS);
        args.add(arg.MSG);
        FUNCTIONS_REQUIRED_ARGS.put("encrypt_with_public_key", args);

        args = new HashSet<arg>();
        args.add(arg.MSG);
        FUNCTIONS_REQUIRED_ARGS.put("decrypt", args);

    }

    /** optional arguments for each AIDL function */
    private static final HashMap<String, Set<arg>> FUNCTIONS_OPTIONAL_ARGS = new HashMap<String, Set<arg>>();
    static {
        HashSet<arg> args = new HashSet<arg>();
        args.add(arg.ENCRYPTION_ALGO);
        args.add(arg.HASH_ALGO);
        args.add(arg.ARMORED);
        args.add(arg.FORCE_V3_SIG);
        args.add(arg.COMPRESSION);
        args.add(arg.PRIVATE_KEY_PASS);
        args.add(arg.SIGNATURE_KEY);
        FUNCTIONS_OPTIONAL_ARGS.put("encrypt_with_passphrase", args);
        FUNCTIONS_OPTIONAL_ARGS.put("encrypt_with_public_key", args);

        args = new HashSet<arg>();
        args.add(arg.SYM_KEY);
        args.add(arg.PUBLIC_KEYS);
        args.add(arg.PRIVATE_KEY_PASS);
        FUNCTIONS_OPTIONAL_ARGS.put("decrypt", args);
    }

    /** a map from ApgService parameters to function calls to get the default */
    private static final HashMap<arg, String> FUNCTIONS_DEFAULTS = new HashMap<arg, String>();
    static {
        FUNCTIONS_DEFAULTS.put(arg.ENCRYPTION_ALGO, "getDefaultEncryptionAlgorithm");
        FUNCTIONS_DEFAULTS.put(arg.HASH_ALGO, "getDefaultHashAlgorithm");
        FUNCTIONS_DEFAULTS.put(arg.ARMORED, "getDefaultAsciiArmour");
        FUNCTIONS_DEFAULTS.put(arg.FORCE_V3_SIG, "getForceV3Signatures");
        FUNCTIONS_DEFAULTS.put(arg.COMPRESSION, "getDefaultMessageCompression");
    }

    /** a map the default functions to their return types */
    private static final HashMap<String, Class<?>> FUNCTIONS_DEFAULTS_TYPES = new HashMap<String, Class<?>>();
    static {
        try {
            FUNCTIONS_DEFAULTS_TYPES.put("getDefaultEncryptionAlgorithm", Preferences.class.getMethod("getDefaultEncryptionAlgorithm").getReturnType());
            FUNCTIONS_DEFAULTS_TYPES.put("getDefaultHashAlgorithm", Preferences.class.getMethod("getDefaultHashAlgorithm").getReturnType());
            FUNCTIONS_DEFAULTS_TYPES.put("getDefaultAsciiArmour", Preferences.class.getMethod("getDefaultAsciiArmour").getReturnType());
            FUNCTIONS_DEFAULTS_TYPES.put("getForceV3Signatures", Preferences.class.getMethod("getForceV3Signatures").getReturnType());
            FUNCTIONS_DEFAULTS_TYPES.put("getDefaultMessageCompression", Preferences.class.getMethod("getDefaultMessageCompression").getReturnType());
        } catch (Exception e) {
            Log.e(TAG, "Function default exception: " + e.getMessage());
        }
    }

    /** a map the default function names to their method */
    private static final HashMap<String, Method> FUNCTIONS_DEFAULTS_METHODS = new HashMap<String, Method>();
    static {
        try {
            FUNCTIONS_DEFAULTS_METHODS.put("getDefaultEncryptionAlgorithm", Preferences.class.getMethod("getDefaultEncryptionAlgorithm"));
            FUNCTIONS_DEFAULTS_METHODS.put("getDefaultHashAlgorithm", Preferences.class.getMethod("getDefaultHashAlgorithm"));
            FUNCTIONS_DEFAULTS_METHODS.put("getDefaultAsciiArmour", Preferences.class.getMethod("getDefaultAsciiArmour"));
            FUNCTIONS_DEFAULTS_METHODS.put("getForceV3Signatures", Preferences.class.getMethod("getForceV3Signatures"));
            FUNCTIONS_DEFAULTS_METHODS.put("getDefaultMessageCompression", Preferences.class.getMethod("getDefaultMessageCompression"));
        } catch (Exception e) {
            Log.e(TAG, "Function method exception: " + e.getMessage());
        }
    }

    /**
     * maps a fingerprint or user id of a key to as master key in database
     * 
     * @param search_key
     *            fingerprint or user id to search for
     * @return master key if found, or 0
     */
    private static long get_master_key(String search_key) {
        if (search_key == null || search_key.length() != 8) {
            return 0;
        }
        ArrayList<String> tmp = new ArrayList<String>();
        tmp.add(search_key);
        long[] _keys = get_master_key(tmp);
        if (_keys.length > 0)
            return _keys[0];
        else
            return 0;
    }

    /**
     * maps fingerprints or user ids of keys to master keys in database
     * 
     * @param search_keys
     *            a list of keys (fingerprints or user ids) to look for in
     *            database
     * @return an array of master keys
     */
    private static long[] get_master_key(ArrayList<String> search_keys) {

        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(KeyRings.TABLE_NAME + " INNER JOIN " + Keys.TABLE_NAME + " ON " + "(" + KeyRings.TABLE_NAME + "." + KeyRings._ID + " = " + Keys.TABLE_NAME
                + "." + Keys.KEY_RING_ID + " AND " + Keys.TABLE_NAME + "." + Keys.IS_MASTER_KEY + " = '1'" + ") " + " INNER JOIN " + UserIds.TABLE_NAME
                + " ON " + "(" + Keys.TABLE_NAME + "." + Keys._ID + " = " + UserIds.TABLE_NAME + "." + UserIds.KEY_ID + " AND " + UserIds.TABLE_NAME + "."
                + UserIds.RANK + " = '0') ");

        String orderBy = UserIds.TABLE_NAME + "." + UserIds.USER_ID + " ASC";

        long now = new Date().getTime() / 1000;
        Cursor mCursor = qb.query(Apg.getDatabase().db(), new String[] {
                KeyRings.TABLE_NAME + "." + KeyRings._ID, // 0
                KeyRings.TABLE_NAME + "." + KeyRings.MASTER_KEY_ID, // 1
                UserIds.TABLE_NAME + "." + UserIds.USER_ID, // 2
                "(SELECT COUNT(tmp." + Keys._ID + ") FROM " + Keys.TABLE_NAME + " AS tmp WHERE " + "tmp." + Keys.KEY_RING_ID + " = " + KeyRings.TABLE_NAME
                        + "." + KeyRings._ID + " AND " + "tmp." + Keys.IS_REVOKED + " = '0' AND " + "tmp." + Keys.CAN_ENCRYPT + " = '1')", // 3
                "(SELECT COUNT(tmp." + Keys._ID + ") FROM " + Keys.TABLE_NAME + " AS tmp WHERE " + "tmp." + Keys.KEY_RING_ID + " = " + KeyRings.TABLE_NAME
                        + "." + KeyRings._ID + " AND " + "tmp." + Keys.IS_REVOKED + " = '0' AND " + "tmp." + Keys.CAN_ENCRYPT + " = '1' AND " + "tmp."
                        + Keys.CREATION + " <= '" + now + "' AND " + "(tmp." + Keys.EXPIRY + " IS NULL OR " + "tmp." + Keys.EXPIRY + " >= '" + now + "'))", // 4
        }, KeyRings.TABLE_NAME + "." + KeyRings.TYPE + " = ?", new String[] {
            "" + Id.database.type_public
        }, null, null, orderBy);

        ArrayList<Long> _master_keys = new ArrayList<Long>();
        while (mCursor.moveToNext()) {
            long _cur_mkey = mCursor.getLong(1);
            String _cur_user = mCursor.getString(2);
            Log.d(TAG, "current master key: " + _cur_mkey + " from " + _cur_user);
            if (search_keys.contains(Apg.getSmallFingerPrint(_cur_mkey)) || search_keys.contains(_cur_user)) {
                Log.d(TAG, "master key found for: " + Apg.getSmallFingerPrint(_cur_mkey));
                _master_keys.add(_cur_mkey);
            }
        }
        mCursor.close();

        long[] _master_longs = new long[_master_keys.size()];
        int i = 0;
        for (Long _key : _master_keys) {
            _master_longs[i++] = _key;
        }
        return _master_longs;
    }

    /**
     * Add default arguments if missing
     * 
     * @param args
     *            the bundle to add default parameters to if missing
     */
    private void add_default_arguments(String call, Bundle args) {
        Preferences _mPreferences = Preferences.getPreferences(getBaseContext(), true);

        Iterator<arg> _iter = FUNCTIONS_DEFAULTS.keySet().iterator();
        while (_iter.hasNext()) {
            arg _current_arg = _iter.next();
            String _current_key = _current_arg.name();
            if (!args.containsKey(_current_key) && FUNCTIONS_OPTIONAL_ARGS.get(call).contains(_current_arg)) {
                String _current_function_name = FUNCTIONS_DEFAULTS.get(_current_arg);
                try {
                    Class<?> _ret_type = FUNCTIONS_DEFAULTS_TYPES.get(_current_function_name);
                    if (_ret_type == String.class) {
                        args.putString(_current_key, (String) FUNCTIONS_DEFAULTS_METHODS.get(_current_function_name).invoke(_mPreferences));
                    } else if (_ret_type == boolean.class) {
                        args.putBoolean(_current_key, (Boolean) FUNCTIONS_DEFAULTS_METHODS.get(_current_function_name).invoke(_mPreferences));
                    } else if (_ret_type == int.class) {
                        args.putInt(_current_key, (Integer) FUNCTIONS_DEFAULTS_METHODS.get(_current_function_name).invoke(_mPreferences));
                    } else {
                        Log.e(TAG, "Unknown return type " + _ret_type.toString() + " for default option");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Exception in add_default_arguments " + e.getMessage());
                }
            }
        }
    }

    /**
     * updates a Bundle with default return values
     * 
     * @param pReturn
     *            the Bundle to update
     */
    private void add_default_returns(Bundle pReturn) {
        ArrayList<String> errors = new ArrayList<String>();
        ArrayList<String> warnings = new ArrayList<String>();

        pReturn.putStringArrayList(ret.ERRORS.name(), errors);
        pReturn.putStringArrayList(ret.WARNINGS.name(), warnings);
    }

    /**
     * checks for required arguments and adds them to the error if missing
     * 
     * @param function
     *            the functions required arguments to check for
     * @param pArgs
     *            the Bundle of arguments to check
     * @param pReturn
     *            the bundle to write errors to
     */
    private void check_required_args(String function, Bundle pArgs, Bundle pReturn) {
        Iterator<arg> _iter = FUNCTIONS_REQUIRED_ARGS.get(function).iterator();
        while (_iter.hasNext()) {
            String _cur_arg = _iter.next().name();
            if (!pArgs.containsKey(_cur_arg)) {
                pReturn.getStringArrayList(ret.ERRORS.name()).add("Argument missing: " + _cur_arg);
            }
        }
    }

    /**
     * checks for unknown arguments and add them to warning if found
     * 
     * @param function
     *            the functions name to check against
     * @param pArgs
     *            the Bundle of arguments to check
     * @param pReturn
     *            the bundle to write warnings to
     */
    private void check_unknown_args(String function, Bundle pArgs, Bundle pReturn) {
        HashSet<arg> all_args = new HashSet<arg>(FUNCTIONS_REQUIRED_ARGS.get(function));
        all_args.addAll(FUNCTIONS_OPTIONAL_ARGS.get(function));

        ArrayList<String> _unknown_args = new ArrayList<String>();
        Iterator<String> _iter = pArgs.keySet().iterator();
        while (_iter.hasNext()) {
            String _cur_key = _iter.next();
            try {
                arg _cur_arg = arg.valueOf(_cur_key);
                if (!all_args.contains(_cur_arg)) {
                    pReturn.getStringArrayList(ret.WARNINGS.name()).add("Unknown argument: " + _cur_key);
                    _unknown_args.add(_cur_key);
                }
            } catch (Exception e) {
                pReturn.getStringArrayList(ret.WARNINGS.name()).add("Unknown argument: " + _cur_key);
                _unknown_args.add(_cur_key);
            }
        }

        // remove unknown arguments so our bundle has just what we need
        for (String _arg : _unknown_args) {
            pArgs.remove(_arg);
        }
    }

    private boolean prepare_args(String call, Bundle pArgs, Bundle pReturn) {
        Apg.initialize(getBaseContext());

        /* add default return values for all functions */
        add_default_returns(pReturn);

        /* add default arguments if missing */
        add_default_arguments(call, pArgs);
        Log.v(TAG, "add_default_arguments");

        /* check for required arguments */
        check_required_args(call, pArgs, pReturn);
        Log.v(TAG, "check_required_args");

        /* check for unknown arguments and add to warning if found */
        check_unknown_args(call, pArgs, pReturn);
        Log.v(TAG, "check_unknown_args");

        /* return if errors happened */
        if (pReturn.getStringArrayList(ret.ERRORS.name()).size() != 0) {
            pReturn.putInt(ret.ERROR.name(), error.ARGUMENTS_MISSING.ordinal());
            return false;
        }
        Log.v(TAG, "error return");

        return true;
    }

    private boolean encrypt(Bundle pArgs, Bundle pReturn) {

        long _pub_master_keys[] = {};
        if (pArgs.containsKey(arg.PUBLIC_KEYS.name())) {
            ArrayList<String> _list = pArgs.getStringArrayList(arg.PUBLIC_KEYS.name());
            ArrayList<String> _pub_keys = new ArrayList<String>();
            Log.v(TAG, "Long size: " + _list.size());
            Iterator<String> _iter = _list.iterator();
            while (_iter.hasNext()) {
                _pub_keys.add(_iter.next());
            }
            _pub_master_keys = get_master_key(_pub_keys);
        }

        InputStream _inStream = new ByteArrayInputStream(pArgs.getString(arg.MSG.name()).getBytes());
        InputData _in = new InputData(_inStream, 0); // XXX Size second param?

        OutputStream _out = new ByteArrayOutputStream();
        try {
            Apg.encrypt(getBaseContext(), // context
                    _in, // input stream
                    _out, // output stream
                    pArgs.getBoolean(arg.ARMORED.name()), // armored
                    _pub_master_keys, // encryption keys
                    get_master_key(pArgs.getString(arg.SIGNATURE_KEY.name())), // signature key
                    pArgs.getString(arg.PRIVATE_KEY_PASS.name()), // signature passphrase
                    null, // progress
                    pArgs.getInt(arg.ENCRYPTION_ALGO.name()), // encryption
                    pArgs.getInt(arg.HASH_ALGO.name()), // hash
                    pArgs.getInt(arg.COMPRESSION.name()), // compression
                    pArgs.getBoolean(arg.FORCE_V3_SIG.name()), // mPreferences.getForceV3Signatures(),
                    pArgs.getString(arg.SYM_KEY.name()) // passPhrase
                    );
        } catch (Exception e) {
            Log.e(TAG, "Exception in encrypt");
            pReturn.getStringArrayList(ret.ERRORS.name()).add("Internal failure (" + e.getClass() + ") in APG when encrypting: " + e.getMessage());

            pReturn.putInt(ret.ERROR.name(), error.APG_FAILURE.ordinal());
            return false;
        }
        Log.v(TAG, "Encrypted");
        pReturn.putString(ret.RESULT.name(), _out.toString());
        return true;
    }

    private final IApgService.Stub mBinder = new IApgService.Stub() {

        public boolean encrypt_with_public_key(Bundle pArgs, Bundle pReturn) {
            if (!prepare_args("encrypt_with_public_key", pArgs, pReturn)) {
                return false;
            }

            return encrypt(pArgs, pReturn);
        }

        public boolean encrypt_with_passphrase(Bundle pArgs, Bundle pReturn) {
            if (!prepare_args("encrypt_with_passphrase", pArgs, pReturn)) {
                return false;
            }

            return encrypt(pArgs, pReturn);

        }

        public boolean decrypt(Bundle pArgs, Bundle pReturn) {
            if (!prepare_args("decrypt", pArgs, pReturn)) {
                return false;
            }

            String _passphrase = pArgs.getString(arg.SYM_KEY.name()) != null ? pArgs.getString(arg.SYM_KEY.name()) : pArgs.getString(arg.PRIVATE_KEY_PASS
                    .name());

            InputStream inStream = new ByteArrayInputStream(pArgs.getString(arg.MSG.name()).getBytes());
            InputData in = new InputData(inStream, 0); // XXX what size in second parameter?
            OutputStream out = new ByteArrayOutputStream();
            try {
                Apg.decrypt(getBaseContext(), in, out, _passphrase, null, // progress
                        pArgs.getString(arg.SYM_KEY.name()) != null // symmetric
                        );
            } catch (Exception e) {
                Log.e(TAG, "Exception in decrypt");
                if (e.getMessage() == getBaseContext().getString(R.string.error_noSecretKeyFound)) {
                    pReturn.getStringArrayList(ret.ERRORS.name()).add("Cannot decrypt: " + e.getMessage());
                    pReturn.putInt(ret.ERROR.name(), error.NO_MATCHING_SECRET_KEY.ordinal());
                } else {
                    pReturn.getStringArrayList(ret.ERRORS.name()).add("Internal failure (" + e.getClass() + ") in APG when decrypting: " + e.getMessage());
                    pReturn.putInt(ret.ERROR.name(), error.APG_FAILURE.ordinal());
                }
                return false;
            }

            pReturn.putString(ret.RESULT.name(), out.toString());
            return true;
        }
    };
}
