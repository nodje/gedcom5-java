/*
 * Copyright 2011 Foundation for On-Line Genealogy, Inc.
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

package org.folg.gedcom.tools;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.folg.gedcom.model.*;
import org.folg.gedcom.parser.JsonParser;
import org.folg.gedcom.parser.ModelParser;
import org.folg.gedcom.parser.TreeParser;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.xml.sax.SAXParseException;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.stream.IntStream;

import static org.folg.gedcom.tools.Gedcom2Json.AncestorEnum.*;
import static org.folg.gedcom.tools.Gedcom2Json.HeaderEnum.*;

/**
 * User: Dallan
 * Date: 1/1/12
 */
public class Gedcom2Json {
    @Option(name = "-i", required = true, usage = "gedcom file in")
    private File gedcomIn;

    @Option(name = "-o", required = false, usage = "json file out")
    private File jsonOut;

    @Option(name = "-t", required = false, usage = "use tree parser (use model parser by default)")
    private boolean useTreeParser = false;

    public static void main(String[] args) throws SAXParseException, IOException {
        Gedcom2Json self = new Gedcom2Json();
        CmdLineParser parser = new CmdLineParser(self);
        try {
            parser.parseArgument(args);
            self.doMain();
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            parser.printUsage(System.err);
        }
    }

    private static boolean containsHanScript(String s) {
        String CHINESE_COMMON_SYMBOLS_RANGE = "\\u4E00-\\u9FFF";
        String CHINESE_CJK_SYMBOLS_RANGE = "\\u3000-\\u303F";
        String CHINESE_RARE_SYMBOLS_RANGE = "\\u3400-\\u4DBF\\U00020000-\\U0002A6DF\\U0002A700-\\U0002B73F\\U0002A740-\\U0002B81F\\U0002B820-\\U0002CEAF";

//        if (s.matches("[\\u4E00-\\u9FA5]+")) {
//            System.out.println("is Chinese");
//        }
        return s.codePoints().anyMatch(
                codepoint ->
                        Character.UnicodeScript.of(codepoint) == Character.UnicodeScript.HAN);
    }

    private static String[] headers() {
        List<String> headers = new ArrayList();
        for (AncestorEnum ancestorId : AncestorEnum.values()) {
            for (HeaderEnum header: HeaderEnum.values())
                headers.add(ancestorId.name() + header.name());
        }
        return headers.toArray(new String[headers.size()]);
    }

    //returns only Han char from the given string
    private String extractHanScriptfrom(String str) {
        if (str == null)
            return "";
        IntStream intStream = str.codePoints();
        String filtered = intStream.filter(ch -> Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN || ch == 47)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        if (filtered.equals("/"))
            filtered = "";
        return filtered;
    }

    /**
     * build family lineages
     *
     * @param gedcom instance
     * @return a Map of linage indexed by ?
     */
    private Map<String, List<Person>> buildFamilyTree(Gedcom gedcom) {
        Set<Family> processedFamily = new HashSet<Family>();
        Map<String, List<Person>> lineageMap = new HashMap<>();
        for (Family family : gedcom.getFamilies()) {
            if (processedFamily.contains(family))
                continue;
            processedFamily.add(family);
            List<Person> lineage = new ArrayList<Person>();
            List<Person> husbandList = family.getHusbands(gedcom);
            if (husbandList.size() == 0)
                continue;
            //we assume each family has only one husband - it is the case in Philip Tan's ged
            Person familyHusband = family.getHusbandRefs().get(0).getPerson(gedcom);
            lineage.add(familyHusband);
            boolean noMoreAncester = true;
            while (familyHusband.getParentFamilies(gedcom).size() > 0 && noMoreAncester) {
                Family parentFamily = familyHusband.getParentFamilies(gedcom).get(0);
                processedFamily.add(parentFamily);
                husbandList = parentFamily.getHusbands(gedcom);
                if (husbandList.size() == 0) {
                    noMoreAncester = false;
                    continue;
                }
                Person parentHusband = parentFamily.getHusbands(gedcom).get(0);
                lineage.add(parentHusband);
                familyHusband = parentHusband;
            }
            lineageMap.put(lineage.get(0).getNames().get(0).getValue(), lineage);
            //stop at first Philip Tan's family 100 generation !
            //return lineageMap;
        }
        return lineageMap;
    }

    private void doMain() throws SAXParseException, IOException {
        String json;
        JsonParser jsonParser = new JsonParser();
        if (useTreeParser) {
            TreeParser treeParser = new TreeParser();
            List<GedcomTag> gedcomTags = treeParser.parseGedcom(gedcomIn);
            json = jsonParser.toJson(gedcomTags);
        } else {
            ModelParser modelParser = new ModelParser();
            long startTime = System.nanoTime();
            Gedcom gedcom = modelParser.parseGedcom(gedcomIn);
            long endTime = System.nanoTime();
            long duration = (endTime - startTime) / 1000000;
            gedcom.createIndexes();
            //checkForFamilyWithoutHusband(gedcom);
            //checkForExistingParentFamilies(gedcom);
            //checkForExistingMultipleHustbandInFamilies(gedcom);
            Map<String, List<Person>> lineageMap = this.buildFamilyTree(gedcom);
//            printOutLineageNames(lineageMap);
            Set<LineageMap> extract = this.extractGenerationsFromLineages(lineageMap);
            this.createCSVFile(extract);
            // get first family's husband
            //String ref = gedcom.getFamilies().get(0).getHusbandRefs().get(0).getRef();
            //I5465477880020118059 Chee Lin /TAN 陳/
            //gedcom.getPerson(ref);
            json = jsonParser.toJson(gedcom);
        }
        if (jsonOut != null) {
            PrintWriter writer = new PrintWriter(jsonOut);
            writer.println(json);
            writer.close();
        }
//      else {
//         System.out.println(json);
//      }
    }

    private Set<LineageMap> extractGenerationsFromLineages(Map<String, List<Person>> lineageMap) {
        Set<LineageMap> finaldata = new TreeSet<>(new LineageMapSorter());
        int lineageLongerThanTwo = 0;
        for (List<Person> lineage : lineageMap.values()) {
            int size = lineage.size();
            if (size < 2)
                continue;
            lineageLongerThanTwo++;
            // get last person's name
            Person firstAncestor = lineage.get(size - 1);
            Person secondAncestor = lineage.get(size - 2);
            Person thirdAncestor = null;
            Person midAncestor = null;
            Person endAncestor = null;
            if (size > 2)
                thirdAncestor = lineage.get(size - 3);
            if (size > 10) {
                midAncestor = searchValidAncestor(lineage, size - Math.round(size / 3));
                endAncestor = searchValidAncestor(lineage, size - 2 * Math.round(size / 3));
            }
            Map<AncestorEnum, Person> personMap = new EnumMap<>(AncestorEnum.class);
            personMap.put(FIRST, firstAncestor);
            personMap.put(SECOND, secondAncestor);
            personMap.put(THIRD, thirdAncestor);
            personMap.put(MID, midAncestor);
            personMap.put(END, endAncestor);
            finaldata.add(this.populateAncestors(personMap));
        }
        System.out.println("Found " + lineageLongerThanTwo + " lineage longer than 2");
        return finaldata;
    }

    private Person searchValidAncestor(List<Person> lineage, int initialPosition) {
        int newPosition = initialPosition;
        if (!this.isPositionValid(lineage, newPosition)) {
            boolean isValid = false;
            int deviation = 1;
            while (!isValid) {
                newPosition = initialPosition + deviation;
                isValid = this.isPositionValid(lineage, newPosition);
                if (!isValid) {
                    newPosition = initialPosition - deviation;
                    isValid = this.isPositionValid(lineage, newPosition);
                }
                deviation++;
                if (initialPosition + deviation == lineage.size() || initialPosition - deviation < 0)
                    return null;
            }
        }
        return lineage.get(newPosition);
    }

    private boolean isPositionValid(List<Person> lineage, int position) {
        List<Name> names;
        names = lineage.get(position).getNames();
        if (!names.isEmpty()) {
            return !this.extractHanScriptfrom(names.get(0).getGiven()).isEmpty();
        }
        return false;
    }

    /**
     * Parses the data from the structured PersonMap into a LineageMap(Map<String,String>)
     * of header value ready for CVS writing
     */
    private LineageMap populateAncestors(Map<AncestorEnum, Person> personMap) {
        LineageMap resultMap = new LineageMap();
        for (AncestorEnum ancestorId : AncestorEnum.values()) {
            Person ancestor = personMap.get(ancestorId);
            if (null == ancestor) {
                for (HeaderEnum header : HeaderEnum.values())
                    resultMap.put(ancestorId.name() + header.name(), null);
                continue;
            }
            resultMap.put(ancestorId.name() + _ID, ancestor.getId());
            Name name = ancestor.getNames().get(0); //assume one and only one name
            resultMap.put(ancestorId.name() + __MARRNM, name.getMarriedName());
            resultMap.put(ancestorId.name() + _GIVN, name.getGiven());
            ancestor.getEventsFacts().forEach(eventFact -> {
                if (eventFact.getTag().equals("BIRT"))
                    resultMap.put(ancestorId.name() + _BIRT, eventFact.getDate());
                if (eventFact.getTag().equals("DEAT"))
                    resultMap.put(ancestorId.name() + _DEAT, eventFact.getDate());
            });
        }
        return resultMap;
    }

    private void printOutLineageNames(Map<String, List<Person>> lineageMap) {
        System.out.println("Printing Out lineage names by line (One line One lineage: ");
        for (List<Person> lineage : lineageMap.values()) {
            StringBuilder csvLineage = new StringBuilder();
            for (Person person : lineage) {
                csvLineage.append("'").append(person.getNames().get(0).getGiven()).append("', ");
            }
            System.out.println(csvLineage);
        }
    }

    private void checkForFamilyWithoutHusband(Gedcom gedcom) {
        System.out.println("Showing all families with NO Husband: ");
        for (Family family : gedcom.getFamilies()) {
            if (family.getHusbands(gedcom).size() == 0) {
                System.out.println("Family ID" + family.getId() + " has no husband");
            }
        }
    }

    private void checkForExistingParentFamilies(Gedcom gedcom) {
        System.out.println("Showing all families with more than one parentFamily:");
        for (Family family : gedcom.getFamilies()) {
            List<Person> personList = family.getHusbands(gedcom);
            if (personList.size() < 1)
                continue;
            List<Family> parentFamilies = family.getHusbands(gedcom).get(0).getParentFamilies(gedcom);
            if (parentFamilies.size() > 1) {
                System.out.println("Family ID" + family.getId());
                for (Family fam : parentFamilies)
                    System.out.println("parent family ID" + fam.getId());
            }
        }
    }

    private void checkForExistingMultipleHustbandInFamilies(Gedcom gedcom) {
        System.out.println("Showing all families with more than one husband:");
        for (Family family : gedcom.getFamilies()) {
            if (family.getHusbandRefs().size() > 1) {
                System.out.println("Family ID" + family.getId());
                for (SpouseRef husbandRef : family.getHusbandRefs())
                    System.out.println("SpouseRef ID" + husbandRef.getRef());
            }
        }
    }

    public void createCSVFile(Set<LineageMap> maps) throws IOException {
        FileWriter out = new FileWriter("lineage.csv");
        FileWriter outnohan = new FileWriter("lineage_chinese_filtered.csv");
        //print out all lineages
        try (CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT.withHeader(headers()))) {
            maps.forEach(lineageMap -> {
                List<String> values = new ArrayList<>();
                for (AncestorEnum ancestorId : AncestorEnum.values()) {
                    for (HeaderEnum header : HeaderEnum.values()) {
                        values.add(lineageMap.get(ancestorId.name() + header.name()));
                    }
                }
                try {
                    printer.printRecord(values);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        }
        //print out lineages while removing non Chinese char, and filtering out lines with no chinese char at all
        try (CSVPrinter printer = new CSVPrinter(outnohan, CSVFormat.DEFAULT.withHeader(headers()))) {
            maps.forEach(lineageMap -> {
                List<String> values = new ArrayList<>();
                boolean isEmpty = true;
                for (AncestorEnum ancestorId : AncestorEnum.values()) {
                    for (HeaderEnum header : HeaderEnum.values()) {
                        if (header.equals(__MARRNM) || header.equals(_GIVN)) {
                            String value = this.extractHanScriptfrom(lineageMap.get(ancestorId.name() + header.name()));
                            values.add(value);
                            if (!value.isEmpty())
                                isEmpty = false;
                        } else
                            values.add(lineageMap.get(ancestorId.name() + header.name()));
                    }
                }
                //filter empty content
                if (!isEmpty) {
                    try {
                        printer.printRecord(values);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });
        }
    }

    public enum AncestorEnum {
        FIRST,
        SECOND,
        THIRD,
        MID,
        END
    }

    public enum HeaderEnum {
        _ID,
        __MARRNM,
        _GIVN,
        _BIRT,
        _DEAT,
    }

    /*public static class AncestorSet {

        Set<Ancestor> ancestorSet = new HashSet<Ancestor>();

        AncestorSet() {
            ancestorSet.add(new Ancestor(FIRST));
            ancestorSet.add(new Ancestor(SECOND));
            ancestorSet.add(new Ancestor(THIRD));
            ancestorSet.add(new Ancestor(MID));
            ancestorSet.add(new Ancestor(END));
        }

    }*/

    private static class LineageMap extends HashMap<String, String> {

        private int entryHashCode(String key) {
            return (key.hashCode() ^ this.get(key).hashCode());
        }


        @Override
        public int hashCode() {
            int h = 0;

            for (String key : this.keySet()) {
                if (key.endsWith(_ID.name()))
                    continue;
                if (null != this.get(key)) {
                    h += entryHashCode(key);
                }
            }

            return h;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof LineageMap))
                return false;

            Map<?, ?> m = (Map<?, ?>) o;
            if (this.size() != m.size())
                return false;

            for (String key : this.keySet()) {
                if (key.endsWith(_ID.name()))
                    continue;
                if (null == this.get(key)) {
                    if (!((null == m.get(key)) && m.containsKey(key)))
                        return false;
                } else {
                    if (!this.get(key).equals(m.get(key)))
                        return false;
                }
            }

            return true;
        }

    }

    public static class LineageMapSorter implements Comparator<LineageMap> {
        @Override
        public int compare(LineageMap e1, LineageMap e2) {
            int first = e1.get("FIRST_ID").compareToIgnoreCase(e2.get("FIRST_ID"));
            if (first == 0) {
                int second = e1.get("SECOND_ID").compareToIgnoreCase(e2.get("SECOND_ID"));
                if (second == 0) {
                    String thirdid1 = e1.get("THIRD_ID");
                    String thirdid2 = e2.get("THIRD_ID");
                    if (null != thirdid1)
                        if (null != thirdid2)
                            return e1.get("THIRD_ID").compareToIgnoreCase(e2.get("THIRD_ID"));
                        else
                            return -1;
                    else if (null != thirdid2)
                        return 1;
                    else
                        return 0;
                } else
                    return second;
            } else
                return first;
        }
    }
}
