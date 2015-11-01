/*
 * JBoss, Home of Professional Open Source
 * Copyright 2014 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.aesh.readline;

import org.jboss.aesh.console.Config;
import org.jboss.aesh.readline.editing.EditMode;
import org.jboss.aesh.readline.editing.EditModeBuilder;
import org.jboss.aesh.readline.editing.Emacs;
import org.jboss.aesh.util.LoggerUtil;

import java.io.IOException;
import java.io.InputStream;
import java.util.Scanner;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * @author <a href="mailto:stale.pedersen@jboss.org">Ståle W. Pedersen</a>
 */
public class InputrcParser {

    private static final Pattern quotePattern = Pattern.compile("^\"");
    private static final Pattern metaPattern = Pattern.compile("^(\\\\M|M|Meta)-"); // "M-
    private static final Pattern controlPattern = Pattern.compile("^(\\\\C|C|Control)-"); // "M-

    private static final Logger LOGGER = LoggerUtil.getLogger(InputrcParser.class.getName());

    /**
     * TODO: clean this shit up!
     *
     * Must be able to parse:
     * set variablename value
     * keyname: function-name or macro
     * "keyseq": function-name or macro
     *
     * Lines starting with # are comments
     * Lines starting with $ are conditional init constructs
     *
     */
    protected static EditMode parseInputrc(InputStream inputStream) throws IOException {
        if(inputStream == null) {
                LOGGER.warning("input stream is null, defaulting to emacs mode");
            //TODO: create default emacs edit mode
            return new Emacs();
        }

        Pattern variablePattern = Pattern.compile("^set\\s+(\\S+)\\s+(\\S+)$");
        Pattern commentPattern = Pattern.compile("^#.*");
        Pattern keyQuoteNamePattern = Pattern.compile("(^\"\\\\\\S+)(\":\\s+)(\\S+)");
        Pattern keyNamePattern = Pattern.compile("(^\\S+)(:\\s+)(\\S+)");
        Pattern startConstructs = Pattern.compile("^\\$if");
        Pattern endConstructs = Pattern.compile("^\\$endif");

            Scanner scanner = new Scanner(inputStream).useDelimiter("\n");

            EditModeBuilder editMode = new EditModeBuilder();

            String line;
            boolean constructMode = false;
            while (scanner.hasNext()) {
                line = scanner.next();
                if (line.trim().length() < 1)
                    continue;
                //first check if its a comment
                if (commentPattern.matcher(line).matches())
                    continue;
                else if (startConstructs.matcher(line).matches()) {
                    constructMode = true;
                    continue;
                }
                else if (endConstructs.matcher(line).matches()) {
                    constructMode = false;
                    continue;
                }

                if (!constructMode) {
                    Matcher variableMatcher = variablePattern.matcher(line);
                    if (variableMatcher.matches()) {
                        Variable variable = Variable.findVariable(variableMatcher.group(1));
                        if(variable != null)
                            parseVariables(variable, variableMatcher.group(2), editMode);
                    }
                    //TODO: currently the inputrc parser is posix only
                    if (Config.isOSPOSIXCompatible()) {
                        Matcher keyQuoteMatcher = keyQuoteNamePattern.matcher(line);
                        if (keyQuoteMatcher.matches()) {
                            editMode.addAction(mapQuoteKeys(keyQuoteMatcher.group(1)), keyQuoteMatcher.group(3));
                            /*
                            builder.create().getOperationManager().addOperationIgnoreWorkingMode(
                                    KeyMapper.mapQuoteKeys(keyQuoteMatcher.group(1),
                                            keyQuoteMatcher.group(3)));
                                            */
                        }
                        else {
                            Matcher keyMatcher = keyNamePattern.matcher(line);
                            if (keyMatcher.matches()) {
                                editMode.addAction(mapKeys(keyMatcher.group(1)), keyMatcher.group(3));
                                /*
                                builder.create().getOperationManager().addOperationIgnoreWorkingMode(
                                        KeyMapper.mapKeys(keyMatcher.group(1), keyMatcher.group(3)));
                                        */
                            }
                        }
                    }
                }
            }

        return editMode.create();
    }

    private static void parseVariables(Variable variable, String value, EditModeBuilder editMode) {

        if(VariableValues.getValuesByVariable(variable).contains(value))
            editMode.addVariable(variable, value);
        else
            LOGGER.warning("Variable: "+variable+" do not allow value: "+value);

        /*
        if (variable.equals(EDITING_MODE)) {
            if(EDITING_MODE.getValues().contains(value)) {
                if(value.equals("vi"))
                    builder.mode(Mode.VI);
                else
                    builder.mode(Mode.EMACS);
            }
            // should log some error
            else if(builder.create().isLogging())
                LOGGER.warning("Value "+value+" not accepted for: "+variable+
                        ", only: "+EDITING_MODE.getValues());

        }
        else if(variable.equals(BELL_STYLE.getVariable())) {
            if(BELL_STYLE.getValues().contains(value))
                builder.bellStyle(value);
            else if(builder.create().isLogging())
                LOGGER.warning("Value "+value+" not accepted for: "+variable+
                        ", only: "+BELL_STYLE.getValues());
        }
        else if(variable.equals(HISTORY_SIZE.getVariable())) {
            try {
                builder.historySize(Integer.parseInt(value));
            }
            catch (NumberFormatException nfe) {
                if(builder.create().isLogging())
                    LOGGER.warning("Value "+value+" not accepted for: "
                            +variable+", it must be an integer.");
            }
        }
        else if(variable.equals(DISABLE_COMPLETION.getVariable())) {
            if(DISABLE_COMPLETION.getValues().contains(value)) {
                if(value.equals("on"))
                    builder.disableCompletion(true);
                else
                    builder.disableCompletion(false);
            }
            else if(builder.create().isLogging())
                LOGGER.warning("Value "+value+" not accepted for: "+variable+
                        ", only: "+DISABLE_COMPLETION.getValues());
        }
        */
    }
    private static int[] mapKeys(String keys) {
        boolean meta = false;
        boolean control = false;
        String randomKeys = null;
        String rest = keys;

        //find control/meta
        while(rest != null) {
            if(metaPattern.matcher(rest).find()) {
                meta = true;
                String[] split = metaPattern.split(rest);
                if(split.length > 1)
                    rest = split[1];
                else
                    rest = null;
                continue;
            }

            if(controlPattern.matcher(rest).find()) {
                control = true;
                String[] split = controlPattern.split(rest);
                if(split.length > 1)
                    rest = split[1];
                else
                    rest = null;
                continue;
            }

            randomKeys = rest;
            rest = null;
        }

        return mapRandomKeys(randomKeys, control, meta);
    }

    /**
     * Parse key mapping lines that start with "
     *
     * @param keys that need mapping
     * @return int[] value of keys
     */
    public static int[] mapQuoteKeys(String keys) {
        if(keys != null && keys.length() > 1)
            return mapKeys(keys.substring(1));
        else
            return null;
    }

    /**
     * Map all random keys after meta/control to its proper int value.
     * - yes its a bad method name....
     *
     * @param randomKeys keys after meta/control
     * @param control true or false
     * @param meta true or false
     * @return int mapping based on randomKeys + control/meta
     */
    private static int[] mapRandomKeys(String randomKeys, boolean control, boolean meta) {
        if(randomKeys == null)
            throw null;

        //parse the rest after control/meta
        int[] out;
        int pos = 0;
        if(meta) {
            out = new int[randomKeys.length()+1];
            out[0] = 27;
            pos = 1;
        }
        else
            out = new int[randomKeys.length()];

        int[] random;
        if(control)
            random = convertRandomControlKeys(randomKeys);
        else
            random = convertRandomKeys(randomKeys);

        for(int i=0; i < random.length; i++,pos++)
            out[pos] = random[i];

        return out;
    }

    private static int[] convertRandomKeys(String random) {
        int[] converted = new int[random.length()];
        for(int i=0; i < random.length(); i++)
            converted[i] = (int) random.charAt(i);

        return converted;
    }

    private static int[] convertRandomControlKeys(String random) {
        final int length = random.length();
        final int[] tmpArray = new int[length];

        int index = 0;
        for(int i=0; i < length; i++) {
            final int converted = lookupControlKey(Character.toLowerCase(random.charAt(i)));
            if(converted == -1){
                LOGGER.warning("ERROR parsing "+random+" keys to aesh. Check your inputrc. Ignoring entry!");
            } else {
                tmpArray[index++] = converted;
            }
        }
        if( index != length){
           final int[] trimmedArray = new int[index];
           for(int i = 0; i<index;i++){
               trimmedArray[i] = tmpArray[i];
           }
           return trimmedArray;
        } else {
           return tmpArray;
        }
    }

    private static int lookupControlKey(char c) {
        switch (c) {
            case '@' : return 0;
            case 'a' : return 1;
            case 'b' : return 2;
            case 'c' : return 3;
            case 'd' : return 4;
            case 'e' : return 5;
            case 'f' : return 6;
            case 'g' : return 7;
            case 'h' : return 8;
            case 'i' : return 9;
            case 'j' : return 10;
            case 'k' : return 11;
            case 'l' : return 12;
            case 'm' : return 13;
            case 'n' : return 14;
            case 'o' : return 15;
            case 'p' : return 16;
            case 'q' : return 17;
            case 'r' : return 18;
            case 's' : return 19;
            case 't' : return 20;
            case 'u' : return 21;
            case 'v' : return 22;
            case 'w' : return 23;
            case 'x' : return 24;
            case 'y' : return 25;
            case 'z' : return 26;
            case '[' : return 27;
            case '?' : return Config.isOSPOSIXCompatible() ? 127 : 8;
        }

        return -1;
    }

}
