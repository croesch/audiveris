//----------------------------------------------------------------------------//
//                                                                            //
//                            G l y p h B o a r d                             //
//                                                                            //
//  Copyright (C) Herve Bitteur 2000-2007. All rights reserved.               //
//  This software is released under the GNU General Public License.           //
//  Contact author at herve.bitteur@laposte.net to report bugs & suggestions. //
//----------------------------------------------------------------------------//
//
package omr.glyph.ui;

import omr.constant.ConstantSet;

import omr.glyph.Glyph;
import omr.glyph.GlyphLag;
import omr.glyph.GlyphModel;
import omr.glyph.Shape;
import static omr.script.ScriptRecording.*;

import omr.selection.GlyphEvent;
import omr.selection.GlyphIdEvent;
import omr.selection.GlyphSetEvent;
import omr.selection.SelectionHint;
import omr.selection.UserEvent;

import omr.ui.Board;
import omr.ui.PixelCount;
import omr.ui.field.SField;
import omr.ui.field.SpinnerUtilities;
import static omr.ui.field.SpinnerUtilities.*;
import omr.ui.util.Panel;

import omr.util.Implement;
import omr.log.Logger;
import omr.util.Predicate;
import static omr.util.Synchronicity.*;

import com.jgoodies.forms.builder.*;
import com.jgoodies.forms.layout.*;

import java.awt.Dimension;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.swing.*;
import javax.swing.event.*;

/**
 * Class <code>GlyphBoard</code> defines a board dedicated to the display of
 * {@link Glyph} information, with several spinners : <ol>
 *
 * <li>The universal <b>globalSpinner</b>, to browse through <i>all</i> glyphs
 * currently defined in the lag (note that glyphs can be dynamically created or
 * destroyed). This includes all the various (vertical) sticks (which are
 * special glyphs) built during the previous steps, for example the bar
 * lines. For other instances (such as for HorizontalsBuilder), these would be
 * horizontal sticks.
 *
 * <li>The <b>knownSpinner</b> for known symbols (that is with a defined shape).
 * This spinner is a subset of the globalSpinner.
 *
 * </ol>The ids handled by each of these spinners can dynamically vary, since
 * glyphs can change their status.
 *
 * <p>Any spinner can also be used to select a glyph by directly entering the
 * glyph id value into the spinner field
 *
 * @author Herv&eacute; Bitteur
 * @version $Id$
 */
public class GlyphBoard
    extends Board
    implements ChangeListener // For all spinners

{
    //~ Static fields/initializers ---------------------------------------------

    /** Specific application parameters */
    private static final Constants constants = new Constants();

    /** Usual logger utility */
    private static final Logger logger = Logger.getLogger(GlyphBoard.class);

    /** Events this board is interested in */
    private static final Collection<Class<?extends UserEvent>> eventClasses = new ArrayList<Class<?extends UserEvent>>();

    static {
        eventClasses.add(GlyphEvent.class);
        eventClasses.add(GlyphSetEvent.class);
    }

    //~ Instance fields --------------------------------------------------------

    /** The related glyph model */
    protected final GlyphModel glyphModel;

    /** An active label */
    protected final JLabel active = new JLabel("");

    /** Input: Dump action */
    protected final Action dumpAction = new DumpAction();

    /** Counter of glyph selection */
    protected final JLabel count = new JLabel("");

    /** Input : Deassign action */
    protected final Action deassignAction = new DeassignAction();

    /** Output : glyph shape icon */
    protected final JLabel shapeIcon = new JLabel();

    /** Input / Output : spinner of all glyphs */
    protected JSpinner globalSpinner;

    /** Input / Output : spinner of known glyphs */
    protected JSpinner knownSpinner;

    /** Predicate for known glyphs */
    protected final Predicate<Glyph> knownPredicate = new Predicate<Glyph>() {
        public boolean check (Glyph glyph)
        {
            return (glyph != null) && glyph.isKnown();
        }
    };

    /** Output : shape of the glyph */
    protected final JTextField shapeField = new SField(
        false,
        "Assigned shape for this glyph");

    /** The JGoodies/Form constraints to be used by all subclasses  */
    protected final CellConstraints cst = new CellConstraints();

    /** The JGoodies/Form layout to be used by all subclasses  */
    protected final FormLayout layout = Panel.makeFormLayout(6, 3);

    /** The JGoodies/Form builder to be used by all subclasses  */
    protected final PanelBuilder builder;

    /**
     * We have to avoid endless loop, due to related modifications : When a
     * GLYPH selection is notified, the id spinner is changed, and When an id
     * spinner is changed, the GLYPH selection is notified
     */
    protected boolean selfUpdating = false;

    //~ Constructors -----------------------------------------------------------

    //------------//
    // GlyphBoard //
    //------------//
    /**
     * Create a Glyph Board
     *
     * @param unitName name of the owning unit
     * @param glyphModel the underlying glyph model
     * @param specificGlyphs additional collection of glyphs, or null
     */
    public GlyphBoard (String                     unitName,
                       GlyphModel                 glyphModel,
                       Collection<?extends Glyph> specificGlyphs)
    {
        this(unitName, glyphModel);

        // Model for globalSpinner
        globalSpinner = makeGlyphSpinner(
            glyphModel.getLag(),
            specificGlyphs,
            null);
        globalSpinner.setName("globalSpinner");
        globalSpinner.setToolTipText("General spinner for any glyph id");

        // Model for knownSpinner
        knownSpinner = makeGlyphSpinner(
            glyphModel.getLag(),
            specificGlyphs,
            knownPredicate);
        knownSpinner.setName("knownSpinner");
        knownSpinner.setToolTipText("Specific spinner for known glyphs");

        // Layout
        int r = 3; // --------------------------------
        builder.addLabel("Id", cst.xy(1, r));
        builder.add(globalSpinner, cst.xy(3, r));

        builder.addLabel("Known", cst.xy(5, r));
        builder.add(knownSpinner, cst.xy(7, r));
    }

    //------------//
    // GlyphBoard //
    //------------//
    /**
     * Basic constructor, to set common characteristics
     *
     * @param name the name assigned to this board instance
     * @param glyphModel the related glyph model, if any
     */
    protected GlyphBoard (String     name,
                          GlyphModel glyphModel)
    {
        super(
            name,
            glyphModel.getLag().getEventService(),
            eventClasses);

        this.glyphModel = glyphModel;

        // Until a glyph selection is made
        dumpAction.setEnabled(false);
        deassignAction.setEnabled(false);

        // Force a constant height for the shapeIcon field, despite the
        // variation in size of the icon
        Dimension dim = new Dimension(
            constants.shapeIconWidth.getValue(),
            constants.shapeIconHeight.getValue());
        shapeIcon.setPreferredSize(dim);
        shapeIcon.setMaximumSize(dim);
        shapeIcon.setMinimumSize(dim);

        // Precise layout
        layout.setColumnGroups(
            new int[][] {
                { 1, 5, 9 },
                { 3, 7, 11 }
            });

        builder = new PanelBuilder(layout, getComponent());
        builder.setDefaultDialogBorder();

        defineLayout();
    }

    //~ Methods ----------------------------------------------------------------

    //-------------------//
    // getDeassignAction //
    //-------------------//
    /**
     * Give access to the Deassign Action, to modify its properties
     *
     * @return the deassign action
     */
    public Action getDeassignAction ()
    {
        return deassignAction;
    }

    //---------//
    // onEvent //
    //---------//
    /**
     * Call-back triggered when Glyph Selection has been modified
     *
     * @param event of current glyph or glyph set
     */
    @Override
    public void onEvent (UserEvent event)
    {
        if (logger.isFineEnabled()) {
            logger.fine(
                "GlyphBoard selfUpdating=" + selfUpdating + " : " + event);
        }

        if (event instanceof GlyphEvent) {
            // Display Glyph parameters (while preventing circular updates)
            selfUpdating = true;

            GlyphEvent glyphEvent = (GlyphEvent) event;
            Glyph      glyph = glyphEvent.getData();

            // Active ?
            if (glyph != null) {
                if (glyph.isActive()) {
                    active.setText("Active");
                } else {
                    active.setText("Non Active");
                }
            } else {
                active.setText("");
            }

            // Dump button and deassign button
            dumpAction.setEnabled(glyph != null);
            deassignAction.setEnabled((glyph != null) && glyph.isKnown());

            // Shape text and icon
            Shape shape = (glyph != null) ? glyph.getShape() : null;

            if (shape != null) {
                shapeField.setText(shape.toString());
                shapeIcon.setIcon(shape.getIcon());
            } else {
                shapeField.setText("");
                shapeIcon.setIcon(null);
            }

            // Global Spinner
            if (globalSpinner != null) {
                if (glyph != null) {
                    globalSpinner.setValue(glyph.getId());
                } else {
                    globalSpinner.setValue(NO_VALUE);
                }
            }

            // Known Spinner
            if (knownSpinner != null) {
                if (glyph != null) {
                    knownSpinner.setValue(
                        knownPredicate.check(glyph) ? glyph.getId() : NO_VALUE);
                } else {
                    knownSpinner.setValue(NO_VALUE);
                }
            }

            selfUpdating = false;
        } else if (event instanceof GlyphSetEvent) {
            // Display count of glyphs in the glyph set
            GlyphSetEvent glyphsEvent = (GlyphSetEvent) event;
            List<Glyph>   glyphs = glyphsEvent.getData();

            if ((glyphs != null) && (glyphs.size() > 0)) {
                count.setText(Integer.toString(glyphs.size()));
            } else {
                count.setText("");
            }
        }
    }

    //--------------//
    // stateChanged //
    //--------------//
    /**
     * CallBack triggered by a change in one of the spinners.
     *
     * @param e the change event, this allows to retrieve the originating
     *          spinner
     */
    @Implement(ChangeListener.class)
    public void stateChanged (ChangeEvent e)
    {
        JSpinner spinner = (JSpinner) e.getSource();

        //  Nota: this method is automatically called whenever the spinner value
        //  is changed, including when a GLYPH selection notification is
        //  received leading to such selfUpdating. So the check.
        if (!selfUpdating) {
            // Notify the new glyph id
            eventService.publish(
                new GlyphIdEvent(
                    this,
                    SelectionHint.GLYPH_INIT,
                    null,
                    (Integer) spinner.getValue()));
        }
    }

    //--------------//
    // defineLayout //
    //--------------//
    /**
     * Define the layout for common fields of all GlyphBoard classes
     */
    protected void defineLayout ()
    {
        int r = 1; // --------------------------------
                   // Glyph ---

        builder.addSeparator("Glyph", cst.xyw(1, r, 6));
        builder.add(active, cst.xy(7, r));
        builder.add(count, cst.xy(9, r));
        builder.add(new JButton(dumpAction), cst.xy(11, r));

        r += 2; // --------------------------------
                // Shape Icon (start, spans several rows)

        builder.add(shapeIcon, cst.xywh(1, r, 1, 5));

        r += 2; // --------------------------------
                // Deassign

        JButton deassignButton = new JButton(deassignAction);
        deassignButton.setHorizontalTextPosition(SwingConstants.LEFT);
        deassignButton.setHorizontalAlignment(SwingConstants.RIGHT);
        builder.add(deassignButton, cst.xy(3, r));
        builder.add(shapeField, cst.xyw(5, r, 7));
    }

    //------------------//
    // makeGlyphSpinner //
    //------------------//
    /**
     * Convenient method to allocate a glyph-based spinner
     *
     * @param lag the underlying glyph lag
     * @param specificGlyphs additional specific glyph collection, or null
     * @param predicate a related glyph predicate, if any
     * @return the spinner built
     */
    protected JSpinner makeGlyphSpinner (GlyphLag                   lag,
                                         Collection<?extends Glyph> specificGlyphs,
                                         Predicate<Glyph>           predicate)
    {
        JSpinner spinner = new JSpinner();
        spinner.setModel(new SpinnerGlyphModel(lag, specificGlyphs, predicate));
        spinner.addChangeListener(this);
        SpinnerUtilities.setRightAlignment(spinner);
        SpinnerUtilities.setEditable(spinner, true);

        return spinner;
    }

    //~ Inner Classes ----------------------------------------------------------

    //-----------//
    // Constants //
    //-----------//
    private static final class Constants
        extends ConstantSet
    {
        //~ Instance fields ----------------------------------------------------

        /** Exact pixel height for the shape icon field */
        PixelCount shapeIconHeight = new PixelCount(
            70,
            "Exact pixel height for the shape icon field");

        /** Exact pixel width for the shape icon field */
        PixelCount shapeIconWidth = new PixelCount(
            50,
            "Exact pixel width for the shape icon field");
    }

    //----------------//
    // DeassignAction //
    //----------------//
    private class DeassignAction
        extends AbstractAction
    {
        //~ Constructors -------------------------------------------------------

        public DeassignAction ()
        {
            super("Deassign");
            this.putValue(Action.SHORT_DESCRIPTION, "Deassign shape");
        }

        //~ Methods ------------------------------------------------------------

        @Implement(ChangeListener.class)
        public void actionPerformed (ActionEvent e)
        {
            if ((glyphModel != null) && (eventList.size() > 0)) {
                if (eventList.size() > 1) {
                    // We have selections for glyph and for glyph set
                    GlyphEvent    glyphEvent = (GlyphEvent) eventService.getLastEvent(
                        GlyphEvent.class);
                    Glyph         glyph = (glyphEvent != null)
                                          ? glyphEvent.getData() : null;

                    GlyphSetEvent glyphsEvent = (GlyphSetEvent) eventService.getLastEvent(
                        GlyphSetEvent.class);
                    List<Glyph>   glyphs = glyphsEvent.getData();
                    glyphModel.deassignSetShape(ASYNC, glyphs, RECORDING);

                    // Update focus on current glyph, even if reused in a compound
                    Glyph newGlyph = glyph.getFirstSection()
                                          .getGlyph();
                    eventService.publish(
                        new GlyphEvent(
                            this,
                            SelectionHint.GLYPH_INIT,
                            null,
                            newGlyph));
                } else if (eventList.size() == 1) {
                    // We have selection for glyph only
                    GlyphEvent glyphEvent = (GlyphEvent) eventService.getLastEvent(
                        GlyphEvent.class);
                    Glyph      glyph = (glyphEvent != null)
                                       ? glyphEvent.getData() : null;
                    glyphModel.deassignGlyphShape(ASYNC, glyph, RECORDING);
                }
            }
        }
    }

    //------------//
    // DumpAction //
    //------------//
    private class DumpAction
        extends AbstractAction
    {
        //~ Constructors -------------------------------------------------------

        public DumpAction ()
        {
            super("Dump");
            this.putValue(Action.SHORT_DESCRIPTION, "Dump this glyph");
        }

        //~ Methods ------------------------------------------------------------

        @Implement(ChangeListener.class)
        public void actionPerformed (ActionEvent e)
        {
            // Retrieve current glyph selection
            GlyphEvent glyphEvent = (GlyphEvent) eventService.getLastEvent(
                GlyphEvent.class);
            Glyph      glyph = glyphEvent.getData();

            if (glyph != null) {
                glyph.dump();
            }
        }
    }
}
