package gov.nist.toolkit.testengine.engine.validations.evs

import gov.nist.toolkit.pluginSupport.loader.PluginClassLoader
import gov.nist.toolkit.testkitutilities.TestKit
import gov.nist.toolkit.testkitutilities.TestKitSearchPath

class EVSValidatorLoader extends PluginClassLoader {

    EVSValidatorLoader(String... paths) throws IOException {
        super(paths)
    }

    EVSValidatorLoader(TestKitSearchPath testKitSearchPath) {
        super(testKitSearchPath.getPluginDirs(TestKit.PluginType.EVS_VALIDATOR));
    }

}
