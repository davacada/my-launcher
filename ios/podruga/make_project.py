import os, hashlib, sys
root=sys.argv[1]
def uid(s): return hashlib.md5(s.encode()).hexdigest()[:24].upper()
srcs=sorted(f for f in os.listdir(f'{root}/Sources') if f.endswith('.swift'))
fonts=sorted(f for f in os.listdir(f'{root}/Resources/Fonts') if f.endswith('.ttf'))
res=[('Assets.xcassets','Resources/Assets.xcassets','folder.assetcatalog'),('chime.wav','Resources/chime.wav','audio.wav')]+[(f,f'Resources/Fonts/{f}','file') for f in fonts]
objs=[]
def o(s): objs.append(s)
P=uid('project'); T=uid('target'); MAIN=uid('maingroup'); SG=uid('srcgroup'); RG=uid('resgroup'); PG=uid('products'); APP=uid('appref')
SP=uid('sourcesphase'); RP=uid('resphase'); FP=uid('fwphase'); CLP=uid('cfglistp'); CLT=uid('cfglistt')
DP=uid('debugp'); RLP=uid('releasep'); DT=uid('debugt'); RLT=uid('releaset'); INFO=uid('info')
fileref=[];buildf=[]
for s in srcs:
    fileref.append(f'{uid("f"+s)} /* {s} */ = {{isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = {s}; sourceTree = "<group>"; }};')
    buildf.append(f'{uid("b"+s)} /* {s} in Sources */ = {{isa = PBXBuildFile; fileRef = {uid("f"+s)} /* {s} */; }};')
for n,p,t in res:
    fileref.append(f'{uid("f"+p)} /* {n} */ = {{isa = PBXFileReference; lastKnownFileType = {t}; name = "{n}"; path = "{p}"; sourceTree = SOURCE_ROOT; }};')
    buildf.append(f'{uid("b"+p)} /* {n} in Resources */ = {{isa = PBXBuildFile; fileRef = {uid("f"+p)} /* {n} */; }};')
fileref.append(f'{INFO} /* Info.plist */ = {{isa = PBXFileReference; lastKnownFileType = text.plist.xml; path = Info.plist; sourceTree = "<group>"; }};')
fileref.append(f'{APP} /* Podruga.app */ = {{isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = Podruga.app; sourceTree = BUILT_PRODUCTS_DIR; }};')
def settings(debug):
    s={'ASSETCATALOG_COMPILER_APPICON_NAME':'AppIcon','CODE_SIGN_STYLE':'Automatic','CURRENT_PROJECT_VERSION':'1','MARKETING_VERSION':'1.0',
       'INFOPLIST_FILE':'Info.plist','IPHONEOS_DEPLOYMENT_TARGET':'17.0','PRODUCT_BUNDLE_IDENTIFIER':'com.davacada.podruga','PRODUCT_NAME':'Podruga',
       'SDKROOT':'iphoneos','SWIFT_VERSION':'5.0','TARGETED_DEVICE_FAMILY':'1','LD_RUNPATH_SEARCH_PATHS':'"$(inherited) @executable_path/Frameworks"',
       'ENABLE_PREVIEWS':'NO','SWIFT_OPTIMIZATION_LEVEL':'"-Onone"' if debug else '"-O"','DEBUG_INFORMATION_FORMAT':'dwarf' if debug else '"dwarf-with-dsym"',
       'ONLY_ACTIVE_ARCH':'YES' if debug else 'NO','SWIFT_ACTIVE_COMPILATION_CONDITIONS':'DEBUG' if debug else '""','ENABLE_TESTABILITY':'YES' if debug else 'NO'}
    return '{\n'+''.join(f'\t\t\t\t{k} = {v};\n' for k,v in s.items())+'\t\t\t}'
def psettings(debug):
    s={'ALWAYS_SEARCH_USER_PATHS':'NO','CLANG_ENABLE_MODULES':'YES','SDKROOT':'iphoneos','IPHONEOS_DEPLOYMENT_TARGET':'17.0','SWIFT_VERSION':'5.0',
       'GCC_OPTIMIZATION_LEVEL':'0' if debug else 's','ENABLE_STRICT_OBJC_MSGSEND':'YES'}
    if debug: s['SWIFT_ACTIVE_COMPILATION_CONDITIONS']='DEBUG'
    return '{\n'+''.join(f'\t\t\t\t{k} = {v};\n' for k,v in s.items())+'\t\t\t}'
out='// !$*UTF8*$!\n{\n\tarchiveVersion = 1;\n\tclasses = {\n\t};\n\tobjectVersion = 56;\n\tobjects = {\n'
out+='\t\t'+'\n\t\t'.join(buildf)+'\n\t\t'+'\n\t\t'.join(fileref)+'\n'
out+=f'''		{FP} = {{isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0; }};
		{MAIN} = {{isa = PBXGroup; children = ({SG}, {RG}, {INFO}, {PG}); sourceTree = "<group>"; }};
		{SG} /* Sources */ = {{isa = PBXGroup; children = ({", ".join(uid("f"+s) for s in srcs)}); path = Sources; sourceTree = "<group>"; }};
		{RG} /* Resources */ = {{isa = PBXGroup; children = ({", ".join(uid("f"+p) for n,p,t in res)}); name = Resources; sourceTree = "<group>"; }};
		{PG} /* Products */ = {{isa = PBXGroup; children = ({APP}); name = Products; sourceTree = "<group>"; }};
		{T} /* Podruga */ = {{isa = PBXNativeTarget; buildConfigurationList = {CLT}; buildPhases = ({SP}, {FP}, {RP}); buildRules = (); dependencies = (); name = Podruga; productName = Podruga; productReference = {APP}; productType = "com.apple.product-type.application"; }};
		{P} /* Project object */ = {{isa = PBXProject; attributes = {{BuildIndependentTargetsInParallel = 1; LastSwiftUpdateCheck = 1600; LastUpgradeCheck = 1600; TargetAttributes = {{ {T} = {{ CreatedOnToolsVersion = 16.0; }}; }}; }}; buildConfigurationList = {CLP}; compatibilityVersion = "Xcode 14.0"; developmentRegion = en; hasScannedForEncodings = 0; knownRegions = (en, ru, Base); mainGroup = {MAIN}; productRefGroup = {PG}; projectDirPath = ""; projectRoot = ""; targets = ({T}); }};
		{RP} = {{isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = ({", ".join(uid("b"+p) for n,p,t in res)}); runOnlyForDeploymentPostprocessing = 0; }};
		{SP} = {{isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = ({", ".join(uid("b"+s) for s in srcs)}); runOnlyForDeploymentPostprocessing = 0; }};
		{DP} /* Debug */ = {{isa = XCBuildConfiguration; buildSettings = {psettings(True)}; name = Debug; }};
		{RLP} /* Release */ = {{isa = XCBuildConfiguration; buildSettings = {psettings(False)}; name = Release; }};
		{DT} /* Debug */ = {{isa = XCBuildConfiguration; buildSettings = {settings(True)}; name = Debug; }};
		{RLT} /* Release */ = {{isa = XCBuildConfiguration; buildSettings = {settings(False)}; name = Release; }};
		{CLP} = {{isa = XCConfigurationList; buildConfigurations = ({DP}, {RLP}); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; }};
		{CLT} = {{isa = XCConfigurationList; buildConfigurations = ({DT}, {RLT}); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; }};
'''
out+=f'\t}};\n\trootObject = {P} /* Project object */;\n}}\n'
os.makedirs(f'{root}/Podruga.xcodeproj',exist_ok=True)
open(f'{root}/Podruga.xcodeproj/project.pbxproj','w').write(out)
print(len(srcs),len(res))
