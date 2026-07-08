import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.BoundingBox;

import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.util.List;

public class CharBoundingBoxExtractor extends PDFTextStripper {
    
    public CharBoundingBoxExtractor() throws Exception {
        super();
    }
    
    @Override
    protected void writeString(String string, List<TextPosition> textPositions) throws Exception {
        PDPage currentPage = getCurrentPage();
        
        for (TextPosition tp : textPositions) {
            String unicode = tp.getUnicode();
            float x = tp.getXDirAdj();
            float y = tp.getYDirAdj();
            float width = tp.getWidthDirAdj();
            float height = tp.getHeightDir();
            
            System.out.println("=== 字符: " + unicode + " ===");
            System.out.println("基础外框（调整后坐标）:");
            System.out.println("  X: " + x);
            System.out.println("  Y: " + y);
            System.out.println("  宽度: " + width);
            System.out.println("  高度: " + height);
            System.out.println("  右下角: (" + (x + width) + ", " + (y + height) + ")");
            
            float[] bbox = calculatePreciseBoundingBox(tp, currentPage);
            System.out.println("精确外框（字体BoundingBox）:");
            System.out.println("  X: " + bbox[0]);
            System.out.println("  Y: " + bbox[1]);
            System.out.println("  宽度: " + bbox[2]);
            System.out.println("  高度: " + bbox[3]);
            System.out.println();
        }
    }
    
    private float[] calculatePreciseBoundingBox(TextPosition tp, PDPage page) {
        PDFont font = tp.getFont();
        BoundingBox bbox = font.getBoundingBox();
        
        int[] charCodes = tp.getCharacterCodes();
        float xadvance = charCodes.length > 0 ? font.getWidth(charCodes[0]) : 0;
        
        float glyphWidth = xadvance;
        float glyphHeight = bbox.getHeight();
        float glyphLowerY = bbox.getLowerLeftY();
        
        Rectangle2D.Float rect = new Rectangle2D.Float(0, glyphLowerY, glyphWidth, glyphHeight);
        
        AffineTransform at = tp.getTextMatrix().createAffineTransform();
        
        if (font instanceof PDType3Font) {
            at.concatenate(font.getFontMatrix().createAffineTransform());
        } else {
            at.scale(1 / 1000f, 1 / 1000f);
        }
        
        Rectangle2D transformedRect = at.createTransformedShape(rect).getBounds2D();
        
        float pageHeight = page.getMediaBox().getHeight();
        float adjustedX = transformedRect.getX();
        float adjustedY = pageHeight - transformedRect.getY() - transformedRect.getHeight();
        
        return new float[]{
            adjustedX,
            adjustedY,
            (float) transformedRect.getWidth(),
            (float) transformedRect.getHeight()
        };
    }
    
    public static void main(String[] args) throws Exception {
        try (PDDocument document = PDDocument.load(new java.io.File("input.pdf"))) {
            CharBoundingBoxExtractor extractor = new CharBoundingBoxExtractor();
            extractor.setSortByPosition(true);
            extractor.setStartPage(0);
            extractor.setEndPage(document.getNumberOfPages());
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            OutputStreamWriter writer = new OutputStreamWriter(baos);
            extractor.writeText(document, writer);
        }
    }
}
