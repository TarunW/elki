package de.lmu.ifi.dbs.elki.utilities.optionhandling.constraints;

import de.lmu.ifi.dbs.elki.distance.distancefunction.DistanceFunction;
import de.lmu.ifi.dbs.elki.utilities.ClassGenericsUtil;
import de.lmu.ifi.dbs.elki.utilities.UnableToComplyException;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.ClassParameter;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.ParameterException;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.PatternParameter;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.WrongParameterValueException;

/**
 * Global parameter constraint for testing if a given pattern parameter ({@link PatternParameter}) specifies a valid
 * pattern for a given class parameter ({@link ClassParameter}) defining a specific distance function.
 *
 * @author Steffi Wanka
 * @param <D> Distance function type
 */
public class GlobalDistanceFunctionPatternConstraint<D extends DistanceFunction<?, ?>> implements GlobalParameterConstraint {

    /**
     * Class parameter whose restriction class is used to check the validity of the pattern parameter.
     */
    private ClassParameter<D> restrictionClass;

    /**
     * Pattern parameter to be checked for validity.
     */
    private PatternParameter pattern;

    /**
     * Constructs a global parameter constraint for testing if a given pattern parameter is a valid
     * argument for a given distance function of a class parameter.
     *
     * @param pattern    the pattern parameter
     * @param restrClass the class parameter defining a distance function
     */
    public GlobalDistanceFunctionPatternConstraint(PatternParameter pattern, ClassParameter<D> restrClass) {
        this.restrictionClass = restrClass;
        this.pattern = pattern;
    }

    /**
     * Tests if the pattern is valid for the distance function defined by the class parameter. If not so, a parameter exception
     * is thrown.
     *
     */
    public void test() throws ParameterException {
        if (restrictionClass.getRestrictionClass() == null) {
            throw new WrongParameterValueException("Global parameter constraint error.\n" +
                "Restriction class of class parameter " + restrictionClass.getName() + " is null.");
        }

        if (!DistanceFunction.class.isAssignableFrom(restrictionClass.getRestrictionClass())) {
            throw new WrongParameterValueException("Global parameter constraint error.\n" +
                "Class parameter " + restrictionClass.getName() + "doesn't specify a distance function.");
        }
        Class<D> restrClass = restrictionClass.getRestrictionClass();


        try {
            DistanceFunction<?, ?> func = ClassGenericsUtil.instantiate(restrClass, restrictionClass.getValue());
            func.valueOf(pattern.getValue());
        }
        catch (IllegalArgumentException e) {
            throw new WrongParameterValueException("Global parameter constraint error.\n" +
                "Pattern parameter " + pattern.getName() + " is no valid pattern for " +
                "distance function " + restrictionClass.getValue() + ".", e);
        }
        catch (UnableToComplyException e) {
            throw new WrongParameterValueException("Global Parameter Constraint Error.\n" +
                "Cannot instantiate distance function " + restrictionClass.getValue());
        }

    }

    public String getDescription() {
        return "Pattern parameter " + pattern.getName() + " must be a valid pattern for " +
            "distance function parameter " + restrictionClass.getName() + ".";
    }

}
