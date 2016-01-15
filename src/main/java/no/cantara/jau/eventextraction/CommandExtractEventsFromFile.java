package no.cantara.jau.eventextraction;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandProperties;
import no.cantara.jau.eventextraction.dto.Event;
import no.cantara.jau.serviceconfig.dto.EventExtractionTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class CommandExtractEventsFromFile extends HystrixCommand<Integer> {
    private static final Logger log = LoggerFactory.getLogger(CommandExtractEventsFromFile.class);
    private static final String GROUP_KEY = "EXTRACT_EVENTS";
    private static final int COMMAND_TIMEOUT = 10000;
    private static final String ERROR_REGEXP = "\\bERROR\\b";
    private static final String EXCEPTION_REGEXP = ".+Exception[^\\n]+";
    private final EventRepo repo;
    private int lastLineRead;
    private final String filePath;
    private final String groupName;
    private final List<EventExtractionTag> extractionTags;

    protected CommandExtractEventsFromFile(EventRepo repo, int lastLineRead, String filePath,
                                           String groupName, List<EventExtractionTag> extractionTags) {
        super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey(GROUP_KEY))
                .andCommandPropertiesDefaults(HystrixCommandProperties.Setter()
                        .withExecutionTimeoutInMilliseconds(COMMAND_TIMEOUT)));
        this.repo = repo;
        this.lastLineRead = lastLineRead;
        this.filePath = filePath;
        this.groupName = groupName;
        this.extractionTags = extractionTags;
    }

    @Override
    protected Integer run() throws Exception {
        log.trace("Start reading from line {}", lastLineRead);
        List<Event> events = new ArrayList<>();
        try(Stream<Event> lines = lines(Paths.get(filePath))) {
            events = lines.skip(lastLineRead)
                    .filter(line -> {
                        lastLineRead = line.getNumber();
                        for (EventExtractionTag tag : extractionTags) {
                            line.setGroupName(groupName);
                            line.setFileName(filePath);
                            String logLine = line.getLine();
                            boolean isMatch = matchAgainstRegex(tag.regex, logLine);
                            if (isMatch) {
                                line.setTag(tag.tagName);
                                return true;
                            }
                            else if (logLine.contains("ERROR")) {
                                line.setTag("Error");
                                return true;
                            }
                            else if (logLine.contains("Exception")) {
                                line.setTag("Exception");
                                return true;
                            }
//                            else if (matchAgainstRegex(ERROR_REGEXP, logLine)) {
//                                line.setTag(ERROR_REGEXP);
//                                return true;
//                            }
//                            else if (matchAgainstRegex(EXCEPTION_REGEXP, logLine)) {
//                                line.setTag(EXCEPTION_REGEXP);
//                                return true;
//                            }
                        }
                        return false;
                    })
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Error in reading managed service's log file.", e);
        }
        log.trace("Line {} was the last line read from file={}", lastLineRead, filePath);
        log.trace("Matching lines: {} {}", events.size(), events);

        repo.addEvents(events);
        return lastLineRead;
    }

    public boolean matchAgainstRegex(String regex, String logLine) {
        Matcher matcher = Pattern.compile(regex)
                .matcher(logLine);
        return matcher.find();
    }

    public static Stream<Event> lines(Path p) throws IOException {
        BufferedReader bufferedReader = Files.newBufferedReader(p);
        Spliterator<Event> spliterator = new Spliterators.AbstractSpliterator<Event>(
                Long.MAX_VALUE, Spliterator.ORDERED|Spliterator.NONNULL) {
            int lineNumber;
            public boolean tryAdvance(Consumer<? super Event> action) {
                String line;
                try {
                    line = bufferedReader.readLine();
                }
                catch(IOException e) {
                    throw new UncheckedIOException(e);
                }
                if (line == null) {
                    return false;
                }
                action.accept(new Event(++lineNumber, line));
                return true;
            }
        };
        return StreamSupport.stream(spliterator, false).onClose(() -> {
            try {
                bufferedReader.close();
            }
            catch(IOException e) {
                throw new UncheckedIOException(e);

            }
        });
    }
}