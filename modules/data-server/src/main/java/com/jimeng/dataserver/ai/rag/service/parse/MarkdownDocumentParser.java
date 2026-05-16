package com.jimeng.dataserver.ai.rag.service.parse;

import com.jimeng.dataserver.ai.rag.model.DocumentBlock;
import com.jimeng.dataserver.ai.rag.model.ParsedDocument;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Document;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Markdown → Block：用 CommonMark AST 遍历，heading 路径自然形成结构，fenced code 单独成块。
 */
@Slf4j
@Component
public class MarkdownDocumentParser implements DocumentParser {

    private final Parser parser = Parser.builder().build();

    @Override
    public boolean supports(String mimeType, String filename) {
        if (filename != null) {
            String lower = filename.toLowerCase();
            if (lower.endsWith(".md") || lower.endsWith(".markdown")) return true;
        }
        return "text/markdown".equalsIgnoreCase(mimeType) || "text/x-markdown".equalsIgnoreCase(mimeType);
    }

    @Override
    public ParsedDocument parse(InputStream stream, String filename) throws Exception {
        Document doc = (Document) parser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));

        List<DocumentBlock> blocks = new ArrayList<>();
        String[] headingStack = new String[7]; // h1..h6
        String[] title = new String[1];

        doc.accept(new AbstractVisitor() {
            @Override
            public void visit(Heading heading) {
                String text = textOf(heading);
                int level = Math.min(heading.getLevel(), 6);
                headingStack[level] = text;
                for (int i = level + 1; i < headingStack.length; i++) headingStack[i] = null;
                if (level == 1 && title[0] == null) title[0] = text;
            }

            @Override
            public void visit(Paragraph p) {
                String text = textOf(p);
                if (!text.isBlank()) {
                    blocks.add(DocumentBlock.text(currentPath(headingStack), null, text));
                }
            }

            @Override
            public void visit(FencedCodeBlock cb) {
                blocks.add(DocumentBlock.code(currentPath(headingStack), null, cb.getLiteral(), cb.getInfo()));
            }

            @Override
            public void visit(IndentedCodeBlock cb) {
                blocks.add(DocumentBlock.code(currentPath(headingStack), null, cb.getLiteral(), null));
            }

            @Override
            public void visit(BulletList l) {
                blocks.add(DocumentBlock.text(currentPath(headingStack), null, textOf(l)));
            }

            @Override
            public void visit(OrderedList l) {
                blocks.add(DocumentBlock.text(currentPath(headingStack), null, textOf(l)));
            }

            @Override
            public void visit(BlockQuote bq) {
                String text = textOf(bq);
                if (!text.isBlank()) {
                    blocks.add(DocumentBlock.text(currentPath(headingStack), null, "> " + text));
                }
            }
        });

        return ParsedDocument.builder()
                .title(title[0] != null ? title[0] : filename)
                .sourceType("md")
                .blocks(blocks)
                .build();
    }

    private static List<String> currentPath(String[] stack) {
        List<String> path = new ArrayList<>();
        for (String h : stack) if (h != null) path.add(h);
        return path;
    }

    private static String textOf(Node node) {
        StringBuilder sb = new StringBuilder();
        collectText(node, sb);
        return sb.toString().trim();
    }

    private static void collectText(Node node, StringBuilder sb) {
        Node child = node.getFirstChild();
        while (child != null) {
            if (child instanceof Text t) sb.append(t.getLiteral());
            else if (child instanceof Code c) sb.append('`').append(c.getLiteral()).append('`');
            else if (child instanceof SoftLineBreak) sb.append(' ');
            else if (child instanceof Emphasis || child instanceof StrongEmphasis || child instanceof Link
                    || child instanceof ListItem || child instanceof Paragraph) {
                collectText(child, sb);
                if (child instanceof ListItem) sb.append('\n');
            } else {
                collectText(child, sb);
            }
            child = child.getNext();
        }
    }
}
