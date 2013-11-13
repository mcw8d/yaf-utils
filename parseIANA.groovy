@Grab(group = 'net.sf.opencsv', module = 'opencsv', version = '2.3')

import au.com.bytecode.opencsv.CSVReader
import groovy.xml.MarkupBuilder

def rows = new CSVReader(new FileReader(new File("ipfix-information-elements.csv")), ',' as char, '"' as char, 1).readAll()

def writer = new FileWriter(new File('InformationElements.xml'))

def xml = new MarkupBuilder(writer)

xml.fieldDefinitions() {
    rows.each { line ->
        assert line.size() == 12
        def ids = []
        if (line[0].contains("-")) {
            def minMax = line[0].split("-")
            Integer i = minMax[0].toInteger()
            Integer j = minMax[1].toInteger()
            ids = i..j
        } else {
            ids << line[0]
        }
        ids.each { id ->
            field() {
                pen(0)
                elemId(id)
                name(line[1])
                dataType(line[2])
                dataTypeSemantics(line[3])
                status(line[4])
                description(line[5])
                units(line[6])
                range(line[7])
                ref(line[8])
                requester(line[9])
                revision(line[10])
                date(line[11])
            }
        }
    }
}
