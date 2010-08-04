package fi.csc.chipster.tools;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.sf.samtools.BAMFileIndexWriter;
import net.sf.samtools.SAMFileHeader;
import net.sf.samtools.SAMFileWriter;
import net.sf.samtools.SAMFileWriterFactory;
import net.sf.samtools.SAMFileWriterImpl;
import net.sf.samtools.SAMRecord;
import net.sf.samtools.SAMSequenceRecord;
import net.sf.samtools.SAMFileHeader.SortOrder;

import org.apache.log4j.Logger;

import fi.csc.microarray.analyser.java.JavaAnalysisJobBase;
import fi.csc.microarray.client.visualisation.methods.gbrowser.ChunkDataSource;
import fi.csc.microarray.client.visualisation.methods.gbrowser.GenomeBrowser;
import fi.csc.microarray.client.visualisation.methods.gbrowser.dataFetcher.Chunk;
import fi.csc.microarray.client.visualisation.methods.gbrowser.fileFormat.ColumnType;
import fi.csc.microarray.client.visualisation.methods.gbrowser.fileFormat.ElandParser;
import fi.csc.microarray.client.visualisation.methods.gbrowser.fileFormat.Strand;
import fi.csc.microarray.client.visualisation.methods.gbrowser.fileFormat.TsvParser;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.Chromosome;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.RegionContent;
import fi.csc.microarray.messaging.JobState;

/**
 * Tool for preprocessing ELAND and BAM data: converts to BAM, sorts and
 * creates an index.
 */
public class NGSPreprocessorTool extends JavaAnalysisJobBase {
    
    private static final Logger logger = Logger.getLogger(JavaAnalysisJobBase.class);
    
    int MAX_RECORDS_IN_RAM = 100000;
    int INDEX_NUM_REFERENCES = 1000;

    private TsvParser[] parsers = {
            new ElandParser()
    };
    
    
    /**
     * Do something when a piece of Eland data is read.
     */
    interface ElandReaderHandler {
        public void process(RegionContent content);
    }
    
    /**
     * Read chromosome information.
     */
    class ElandChrHandler implements ElandReaderHandler {
        
        public Set<String> chromosomes = new HashSet<String>(); 

        @Override
        public void process(RegionContent content) {
            chromosomes.add(((Chromosome)content.values.get(ColumnType.CHROMOSOME)).toString());
        }
        
    }
    
    /**
     * Read records in an eland file and write to a SAM file.
     */
    class ElandSAMHandler implements ElandReaderHandler {
        
        private SAMFileWriter samWriter;
        
        public ElandSAMHandler(SAMFileWriter samWriter) {
            this.samWriter = samWriter;
        }
        
        @Override
        public void process(RegionContent content) {
            SAMRecord samRecord = new SAMRecord(samWriter.getFileHeader());
            samRecord.setReadName((String)content.values.get(ColumnType.ID));
            samRecord.setAlignmentStart(((Long)content.values.get(ColumnType.BP_START)).intValue());
            samRecord.setReadBases(((String)content.values.get(ColumnType.SEQUENCE)).getBytes());
            samRecord.setReferenceName(((Chromosome)content.values.get(ColumnType.CHROMOSOME)).toString());
            samRecord.setReadNegativeStrandFlag((Strand)content.values.get(ColumnType.STRAND) == Strand.REVERSED);
            samRecord.setCigarString(((String)content.values.get(ColumnType.SEQUENCE)).length() + "M");
            samWriter.addAlignment(samRecord);
        }
        
    }
    
    /**
     * Read Eland file and process it.
     */
    class ElandReader {
        
        private ChunkDataSource elandData;
        private ElandReaderHandler handle;
        
        public ElandReader(ChunkDataSource elandData, ElandReaderHandler handle) {
            this.elandData = elandData;
            this.handle = handle;
        }
        
        public void read() throws IOException {
            long filePosition = 0;
            long bytesRead = 1;
            int increment = 200000;
            byte[] chunkBytes;
            while (bytesRead > 0) {
                // Read some data from ELAND
                chunkBytes = new byte[increment];
                bytesRead = elandData.read(filePosition, chunkBytes);
                String chunkString = new String(chunkBytes);
                int lastNewLine = chunkString.lastIndexOf("\n");
                
                if (lastNewLine == -1) {
                    break;
                }
                
                // Read chunk to a region list
                Chunk chunk = new Chunk(chunkString.substring(0, lastNewLine));
                List<RegionContent> contents = elandData.getFileParser().getAll(chunk,
                        Arrays.asList(new ColumnType[] {
                            ColumnType.ID, ColumnType.CHROMOSOME,
                            ColumnType.BP_START, ColumnType.STRAND,
                            ColumnType.SEQUENCE
                        }));
                
                // Somehow process each region content
                for (RegionContent content : contents) {
                    handle.process(content);
                }
                
                // Increase the file position
                filePosition += lastNewLine + 1;
            }
        }
    }
    
    @Override
    public String getSADL() {
        
        StringBuffer fileFormats = new StringBuffer();
        for (int i = 0; i < parsers.length; i++) {
            fileFormats.append(parsers[i].getName());
            
            if (i < parsers.length - 1) {
                fileFormats.append(", ");
            }
        }
        
        // TODO more verbose name, name of the second parameter
        return  "TOOL Utils / eland_preprocessor : \"ELAND preprocessor\"" + 
                "(Convert ELAND file to BAM, sort it and create an index.)"  + "\n" +
                "INPUT input.tsv TYPE GENERIC" + "\n" +
                "OUTPUT output.bam" + "\n" +
                "OUTPUT output.bai" + "\n" +
                "PARAMETER file.format TYPE [" + fileFormats + "] DEFAULT " + parsers[0].getName() + " ()" + "\n";
     }

    @Override
    protected void execute() { 
        updateState(JobState.RUNNING, "Sorting file");
        
        File inputFile = new File(jobWorkDir, "input.tsv");
        File outputFile = new File(jobWorkDir, "output.bam");
        File indexFile = new File(jobWorkDir, "output.bai");
        
        try {
            elandToBAM(inputFile, outputFile, indexFile);
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        updateState(JobState.RUNNING, "sort finished");
    }
    
    /**
     * Convert Eland format to BAM.
     * 
     * @param elandFile
     * @throws Exception 
     */
    private void elandToBAM(File elandFile, File bamFile, File indexFile) throws Exception {
        // Input file
        ChunkDataSource elandData = new ChunkDataSource(elandFile, new ElandParser());
        
        // Collect chromosomes and later add them to the header
        ElandChrHandler chrHandler = new ElandChrHandler();
        new ElandReader(elandData, chrHandler).read();
        Set<String> chromosomes = chrHandler.chromosomes;
            
        // Create header for output file
        SAMFileHeader samHeader = new SAMFileHeader();
        samHeader.setSortOrder(SortOrder.coordinate);
        for (String chr : chromosomes) {
            samHeader.addSequence(new
                    SAMSequenceRecord(chr,
                    (int)GenomeBrowser.CHROMOSOME_SIZES[Integer.parseInt(chr) - 1]));
        }        
           
        // Create SAM writer
        SAMFileWriterImpl.setDefaultMaxRecordsInRam(MAX_RECORDS_IN_RAM);
        SAMFileWriter samWriter = new SAMFileWriterFactory().
                makeBAMWriter(samHeader, false, bamFile);
        
        // Write records
        ElandSAMHandler samHandler = new ElandSAMHandler(samWriter);
        new ElandReader(elandData, samHandler).read();
        samWriter.close();
        
        // Write index
        BAMFileIndexWriter bamIndex = new BAMFileIndexWriter(
                indexFile, INDEX_NUM_REFERENCES);
        bamIndex.createIndex(bamFile, false, true);
        bamIndex.writeBinary(true, bamFile.length());      
        bamIndex.close();
    }
}
