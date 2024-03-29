package edu.columbia.cs.psl.phosphor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Scanner;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import edu.columbia.cs.psl.phosphor.instrumenter.TaintTrackingClassVisitor;
import edu.columbia.cs.psl.phosphor.runtime.Tainter;

public class Instrumenter {
	public static ClassLoader loader;

	// private static Logger logger = Logger.getLogger("Instrumenter");

	public static int pass_number = 0;

	private static File rootOutputDir;
	private static String lastInstrumentedClass;

	public static int MAX_SANDBOXES = 2;

	static int nChanges = 0;
	static boolean analysisInvalidated = false;


	public static void preAnalysis() {

	}

	public static void finishedAnalysis() {
		System.out.println("Analysis Completed: Beginning Instrumentation Phase");

	}

	static String curPath;


	public static boolean isCollection(String internalName) {
		try {
			Class c;
			if (TaintTrackingClassVisitor.IS_RUNTIME_INST
					&& !internalName.startsWith("java/"))
				return false;
			if (loader == null)
				c = Class.forName(internalName.replace("/", "."));
			else
				c = loader.loadClass(internalName.replace("/", "."));
			if (Collection.class.isAssignableFrom(c))
				return true;
		} catch (Throwable ex) {
		}
		return false;
	}

	
    public static boolean IS_KAFFE_INST = Boolean.valueOf(System.getProperty("KAFFE", "false"));
    public static boolean IS_HARMONY_INST = Boolean.valueOf(System.getProperty("HARMONY", "false"));

	public static boolean IS_ANDROID_INST = Boolean.valueOf(System.getProperty(
			"ANDROID", "false"));

	public static boolean isClassWithHashmapTag(String clazz) {
		if (IS_ANDROID_INST)
			return false;
		return clazz.startsWith("java/lang/Boolean")
				|| clazz.startsWith("java/lang/Character")
				|| clazz.startsWith("java/lang/Byte")
				|| clazz.startsWith("java/lang/Short");
	}

	public static boolean isIgnoredClass(String owner) {
		if(Configuration.taintTagFactory.isIgnoredClass(owner))
			return true;
		if(IS_ANDROID_INST && ! TaintTrackingClassVisitor.IS_RUNTIME_INST)
		{
//			System.out.println("IN ANDROID INST:");
			return owner.startsWith("java/lang/Object")
					|| owner.startsWith("java/lang/Number")
					|| owner.startsWith("java/lang/Comparable")
					|| owner.startsWith("java/lang/ref/SoftReference")
					|| owner.startsWith("java/lang/ref/Reference")
					|| owner.startsWith("java/lang/ref/FinalizerReference")
					// || owner.startsWith("java/awt/image/BufferedImage")
					// || owner.equals("java/awt/Image")
					|| (owner.startsWith("edu/columbia/cs/psl/phosphor") && !owner
							.equals(Type.getInternalName(Tainter.class)))
					|| owner.startsWith("sun/awt/image/codec/");
		} else if (IS_KAFFE_INST || IS_HARMONY_INST) {
			return owner.startsWith("java/lang/Object")
					|| owner.startsWith("java/lang/Boolean")
					|| owner.startsWith("java/lang/Character")
					|| owner.startsWith("java/lang/Byte")
					|| owner.startsWith("java/lang/Short")
					// || owner.startsWith("java/lang/System")
					// || owner.startsWith("org/apache/harmony/drlvm/gc_gen/GCHelper")
					// || owner.startsWith("edu/columbia/cs/psl/microbench")
					// || owner.startsWith("java/lang/Number")
					|| owner.startsWith("java/lang/VMObject")
					|| owner.startsWith("java/lang/VMString")
					|| (IS_KAFFE_INST && owner.startsWith("java/lang/reflect"))
//					|| owner.startsWith("gnu/")
										|| owner.startsWith("java/lang/VMClass")

					|| owner.startsWith("java/lang/Comparable") || owner.startsWith("java/lang/ref/SoftReference") || owner.startsWith("java/lang/ref/Reference")
					//																|| owner.startsWith("java/awt/image/BufferedImage")
					//																|| owner.equals("java/awt/Image")
					|| (owner.startsWith("edu/columbia/cs/psl/phosphor") && ! owner.equals(Type.getInternalName(Tainter.class)))
					||owner.startsWith("sun/awt/image/codec/") || (IS_HARMONY_INST && (owner.equals("java/io/Serializable")));
		}
		else
		return (Configuration.ADDL_IGNORE != null && owner.startsWith(Configuration.ADDL_IGNORE)) || owner.startsWith("java/lang/Object") || owner.startsWith("java/lang/Boolean") || owner.startsWith("java/lang/Character")
				|| owner.startsWith("java/lang/Byte")
				|| owner.startsWith("java/lang/Short")
				|| owner.startsWith("org/jikesrvm") || owner.startsWith("com/ibm/tuningfork") || owner.startsWith("org/mmtk") || owner.startsWith("org/vmmagic")
//				|| owner.startsWith("edu/columbia/cs/psl/microbench")
				|| owner.startsWith("java/lang/Number") || owner.startsWith("java/lang/Comparable") || owner.startsWith("java/lang/ref/SoftReference") || owner.startsWith("java/lang/ref/Reference")
				//																|| owner.startsWith("java/awt/image/BufferedImage")
				//																|| owner.equals("java/awt/Image")
				|| (owner.startsWith("edu/columbia/cs/psl/phosphor") && ! owner.equals(Type.getInternalName(Tainter.class)))
				||owner.startsWith("sun/awt/image/codec/")
								|| (owner.startsWith("sun/reflect/Reflection")) //was on last
				|| owner.equals("java/lang/reflect/Proxy") //was on last
				|| owner.startsWith("sun/reflection/annotation/AnnotationParser") //was on last
				|| owner.startsWith("sun/reflect/MethodAccessor") //was on last
				|| owner.startsWith("org/apache/jasper/runtime/JspSourceDependent")
				|| owner.startsWith("sun/reflect/ConstructorAccessor") //was on last
				|| owner.startsWith("sun/reflect/SerializationConstructorAccessor")
				|| owner.startsWith("sun/reflect/GeneratedMethodAccessor") || owner.startsWith("sun/reflect/GeneratedConstructorAccessor")
				|| owner.startsWith("sun/reflect/GeneratedSerializationConstructor") || owner.startsWith("sun/awt/image/codec/")
				|| owner.startsWith("java/lang/invoke/LambdaForm")
				|| owner.startsWith("com/jprofiler")
//				|| owner.startsWith("com/sun/jmx")
//				|| owner.startsWith("sun/management") || owner.startsWith("javax/management") //|| owner.startsWith("sun/rmi") || owner.startsWith("java/rmi")
				//|| owner.startsWith("java/lang/management")
				;
	}


	public static HashMap<String, ClassNode> classes = new HashMap<String, ClassNode>();
	
	public static boolean definitelyNotEnum(String typeName) {
		if(classes.containsKey(typeName)) {
			ClassNode cn = classes.get(typeName);
			boolean isEnum = (cn.access & Opcodes.ACC_ENUM) != 0;
			assert "java/lang/Enum".equals(cn.superName) == isEnum : cn.name + " " + cn.superName + " and "  + isEnum;
			return !isEnum;
		} else {
			try {
				Class<?> klass = loader.loadClass(typeName.replace('/', '.'));
				return !klass.isEnum();
			} catch (ClassNotFoundException e) {
				return false;
			}
		}
	}

	public static void analyzeClass(InputStream is) {
		ClassReader cr;
		nTotal++;
		try {
			cr = new ClassReader(is);
			cr.accept(new ClassVisitor(Opcodes.ASM5) {
				@Override
				public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
					super.visit(version, access, name, signature, superName, interfaces);
					ClassNode cn = new ClassNode();
					cn.name = name;
					cn.access = access;
					cn.superName = superName;
					cn.interfaces = new ArrayList<String>();
					Instrumenter.classes.put(name, cn);
				}
			}, ClassReader.SKIP_CODE);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	static int nTotal = 0;
	static int n = 0;

	public static byte[] instrumentClass(String path, InputStream is,
			boolean renameInterfaces) {
		try {
			n++;
			if (n % 1000 == 0)
				System.out.println("Processed: " + n + "/" + nTotal);
			curPath = path;
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();

			int nRead;
			byte[] data = new byte[16384];

			while ((nRead = is.read(data, 0, data.length)) != -1) {
				buffer.write(data, 0, nRead);
			}

			buffer.flush();
			PreMain.PCLoggingTransformer transformer = new PreMain.PCLoggingTransformer();
			byte[] ret = transformer.transform(Instrumenter.loader, path, null, null,
					buffer.toByteArray());
			curPath = null;
			return ret;
		} catch (Exception ex) {
			curPath = null;
			ex.printStackTrace();
			return null;
		}
	}

	static Option opt_taintSources = OptionBuilder.withArgName("taintSources").hasArg().withDescription("File with listing of taint sources to auto-taint").create("taintSources");
	static Option opt_taintSinks =   OptionBuilder.withArgName("taintSinks").hasArg().withDescription("File with listing of taint sinks to use to check for auto-taints").create("taintSinks");
	static Option opt_dataTrack = new Option("withoutDataTrack", "Disable taint tracking through data flow (on by default)");
	static Option opt_controlTrack = new Option("controlTrack", "Enable taint tracking through control flow");
	static Option opt_multiTaint = new Option("multiTaint", "Support for 2^32 tags instead of just 32");
	static Option opt_trackArrayLengthTaints = new Option("withArrayLengthTags", "Tracks taint tags on array lengths - requires use of JVMTI runtime library when running");
	static Option opt_withoutFieldHiding = new Option("withoutFieldHiding", "Disable hiding of taint fields via reflection");
	static Option opt_withoutPropogation = new Option("withoutPropogation","Disable all tag propogation - still create method stubs and wrappers as per other options, but don't actually propogate tags");
	static Option opt_enumPropogation = new Option("withEnumsByValue","Propogate tags to enums as if each enum were a value (not a reference) through the Enum.valueOf method");
	static Option opt_unboxAcmpEq = new Option("forceUnboxAcmpEq","At each object equality comparison, ensure that all operands are unboxed (and not boxed types, which may not pass the test)");
	static Option opt_autoTaint = new Option("autoTaint", "At every method return add calls into Staccato");

	static Option help = new Option( "help", "print this message" );

	public static String sourcesFile;
	public static String sinksFile;

	public static void main(String[] args) {

		Options options = new Options();
		options.addOption(help);
		options.addOption(opt_multiTaint);
		options.addOption(opt_controlTrack);
		options.addOption(opt_dataTrack);
		options.addOption(opt_taintSinks);
		options.addOption(opt_taintSources);
		options.addOption(opt_trackArrayLengthTaints);
		options.addOption(opt_withoutFieldHiding);
		options.addOption(opt_withoutPropogation);
		options.addOption(opt_enumPropogation);
		options.addOption(opt_unboxAcmpEq);
		options.addOption(opt_autoTaint);

	    CommandLineParser parser = new BasicParser();
	    CommandLine line = null;
	    try {
	        line = parser.parse( options, args );
	    }
	    catch( org.apache.commons.cli.ParseException exp ) {

			HelpFormatter formatter = new HelpFormatter();
			formatter.printHelp("java -jar phosphor.jar [OPTIONS] [input] [output]",
					options);
			System.err.println(exp.getMessage());
			return;
		}
		if (line.hasOption("help") || line.getArgs().length != 2) {
			HelpFormatter formatter = new HelpFormatter();
			formatter.printHelp("java -jar phosphor.jar [OPTIONS] [input] [output]",
					options);
			return;
		}

		sourcesFile = line.getOptionValue("taintSources");
		sinksFile = line.getOptionValue("taintSinks");
		Configuration.MULTI_TAINTING = line.hasOption("multiTaint");
		Configuration.IMPLICIT_TRACKING = line.hasOption("controlTrack");
		Configuration.DATAFLOW_TRACKING = !line.hasOption("withoutDataTrack");
		if (Configuration.IMPLICIT_TRACKING)
			Configuration.MULTI_TAINTING = true;

		Configuration.ARRAY_LENGTH_TRACKING = line.hasOption("withArrayLengthTags");
		Configuration.WITHOUT_FIELD_HIDING = line.hasOption("withoutFieldHiding");
		Configuration.WITHOUT_PROPOGATION = line.hasOption("withoutPropogation");
		Configuration.WITH_ENUM_BY_VAL = line.hasOption("withEnumsByValue");
		Configuration.WITH_UNBOX_ACMPEQ = line.hasOption("forceUnboxAcmpEq");
		Configuration.AUTO_TAINT = line.hasOption("autoTaint");
		Configuration.init();

		if (Configuration.DATAFLOW_TRACKING)
			System.out.println("Data flow tracking: enabled");
		else
			System.out.println("Data flow tracking: disabled");
		if (Configuration.IMPLICIT_TRACKING) {
			System.out.println("Control flow tracking: enabled");
		} else
			System.out.println("Control flow tracking: disabled");

		if (Configuration.MULTI_TAINTING)
			System.out.println("Multi taint: enabled");
		else
			System.out.println("Taints will be combined with logical-or.");
		
		System.out.println("Enums by val: " + (Configuration.WITH_ENUM_BY_VAL ? "enabled" : "disabled"));
		System.out.println("Unbox acmp: " + (Configuration.WITH_UNBOX_ACMPEQ ? "enabled" : "disabled"));
		System.out.println("Auto-taint: " + (Configuration.AUTO_TAINT ? "enabled": "disabled"));

		TaintTrackingClassVisitor.IS_RUNTIME_INST = false;
		ANALYZE_ONLY = true;
		System.out.println("Starting analysis");
		// preAnalysis();
		_main(line.getArgs());
		System.out.println("Analysis Completed: Beginning Instrumentation Phase");
		// finishedAnalysis();
		ANALYZE_ONLY = false;
		_main(line.getArgs());
		System.out.println("Done");

	}

	static boolean ANALYZE_ONLY;

	public static void _main(String[] args) {
		if(PreMain.DEBUG)
			System.err.println("Warning: Debug output enabled (uses a lot of IO!)");
		String outputFolder = args[1];
		rootOutputDir = new File(outputFolder);
		if (!rootOutputDir.exists())
			rootOutputDir.mkdir();
		String inputFolder = args[0];
		// Setup the class loader
		final ArrayList<URL> urls = new ArrayList<URL>();
		Path input = FileSystems.getDefault().getPath(args[0]);
		try {
			if (Files.isDirectory(input))
				Files.walkFileTree(input, new FileVisitor<Path>() {

					// @Override
					public FileVisitResult preVisitDirectory(Path dir,
							BasicFileAttributes attrs) throws IOException {
						return FileVisitResult.CONTINUE;
					}

					// @Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
							throws IOException {
						if (file.getFileName().toString().endsWith(".jar"))
							urls.add(file.toUri().toURL());
						return FileVisitResult.CONTINUE;
					}

					// @Override
					public FileVisitResult visitFileFailed(Path file, IOException exc)
							throws IOException {
						return FileVisitResult.CONTINUE;
					}

					// @Override
					public FileVisitResult postVisitDirectory(Path dir, IOException exc)
							throws IOException {
						return FileVisitResult.CONTINUE;
					}
				});
			else if (inputFolder.endsWith(".jar"))
				urls.add(new File(inputFolder).toURI().toURL());
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		try {
			urls.add(new File(inputFolder).toURI().toURL());
		} catch (MalformedURLException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		// System.out.println(urls);
		if (args.length == 3) {
			System.out.println("Using extra classpath file: " + args[2]);
			try {
				File f = new File(args[2]);
				if (f.exists() && f.isFile()) {
					Scanner s = new Scanner(f);
					while (s.hasNextLine()) {
						urls.add(new File(s.nextLine()).getCanonicalFile().toURI().toURL());
					}
				} else if (f.isDirectory())
					urls.add(f.toURI().toURL());
			} catch (FileNotFoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (MalformedURLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else if (args.length > 3) {
			for (int i = 2; i < args.length; i++) {
				File f = new File(args[i]);
				if (!f.exists()) {
					System.err.println("Unable to read path " + args[i]);
					System.exit(-1);
				}
				if (f.isDirectory() && !f.getAbsolutePath().endsWith("/"))
					f = new File(f.getAbsolutePath() + "/");
				try {
					if (f.isDirectory()) {

					}
					urls.add(f.getCanonicalFile().toURI().toURL());
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}
		}
		URL[] urlArray = new URL[urls.size()];
		urlArray = urls.toArray(urlArray);
		loader = new URLClassLoader(urlArray, Instrumenter.class.getClassLoader());
		PreMain.bigLoader = loader;

		File f = new File(inputFolder);
		if (!f.exists()) {
			System.err.println("Unable to read path " + inputFolder);
			System.exit(-1);
		}
		if (f.isDirectory())
			processDirectory(f, rootOutputDir, true);
		else if (inputFolder.endsWith(".jar")) {
			// try {
			// FileOutputStream fos = new FileOutputStream(rootOutputDir.getPath() +
			// File.separator + f.getName());
			processJar(f, rootOutputDir);
		// } catch (FileNotFoundException e1) {
		// // TODO Auto-generated catch block
		// e1.printStackTrace();
		// }
		} else if(inputFolder.endsWith(".war")) {
			processZip(f, rootOutputDir);
		} else if (inputFolder.endsWith(".class"))
			try {
				processClass(f.getName(), new FileInputStream(f), rootOutputDir);
			} catch (FileNotFoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		else if (inputFolder.endsWith(".zip")) {
			processZip(f, rootOutputDir);
		} else {
			System.err.println("Unknown type for path " + inputFolder);
			System.exit(-1);
		}

		// generateInterfaceCLinits(rootOutputDir);
		// }

	}

	private static void processClass(String name, InputStream is, File outputDir) {

		try {
			FileOutputStream fos = new FileOutputStream(outputDir.getPath()
					+ File.separator + name);
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			lastInstrumentedClass = outputDir.getPath() + File.separator + name;

			if (ANALYZE_ONLY)
				analyzeClass(is);
			else {
				byte[] c = instrumentClass(outputDir.getAbsolutePath(), is, true);
				bos.write(c);
				bos.writeTo(fos);
				fos.close();
			}
			is.close();

		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	private static void processDirectory(File f, File parentOutputDir,
			boolean isFirstLevel) {
		if (f.getName().equals(".AppleDouble"))
			return;
		File thisOutputDir;
		if (isFirstLevel) {
			thisOutputDir = parentOutputDir;
		} else {
			thisOutputDir = new File(parentOutputDir.getAbsolutePath()
					+ File.separator + f.getName());
			thisOutputDir.mkdir();
		}
		for (File fi : f.listFiles()) {
			if (fi.isDirectory())
				processDirectory(fi, thisOutputDir, false);
			else if (fi.getName().endsWith(".class"))
				try {
					processClass(fi.getName(), new FileInputStream(fi), thisOutputDir);
				} catch (FileNotFoundException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			else if (fi.getName().endsWith(".jar")) {
				// try {
				// FileOutputStream fos = new FileOutputStream(thisOutputDir.getPath() +
				// File.separator + f.getName());
				processJar(fi, thisOutputDir);
			// fos.close();
			// } catch (IOException e1) {
			// TODO Auto-generated catch block
			// e1.printStackTrace();
			// }
			} else if (fi.getName().endsWith(".war")) {
				processZip(fi, thisOutputDir);	
			} else if (fi.getName().endsWith(".zip")) {
				processZip(fi, thisOutputDir);
			} else {
				File dest = new File(thisOutputDir.getPath() + File.separator
						+ fi.getName());
				FileChannel source = null;
				FileChannel destination = null;

				try {
					source = new FileInputStream(fi).getChannel();
					destination = new FileOutputStream(dest).getChannel();
					destination.transferFrom(source, 0, source.size());
				} catch (Exception ex) {
					System.err.println("error copying file " + fi);
					ex.printStackTrace();
					// logger.log(Level.SEVERE, "Unable to copy file " + fi, ex);
					// System.exit(-1);
				} finally {
					if (source != null) {
						try {
							source.close();
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
					if (destination != null) {
						try {
							destination.close();
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
				}

			}
		}

	}

	public static void processJar(File f, File outputDir) {
		try {
			if(f.getName().endsWith("staccato.jar")) {
				System.out.println("skipping staccato");
				try(FileOutputStream fos = new FileOutputStream(outputDir.getPath() + File.separator + f.getName())) { 
					Files.copy(Paths.get(f.getCanonicalPath()), fos);
				}
				return;
			}
			// @SuppressWarnings("resource")
			// System.out.println("File: " + f.getName());
			JarFile jar = new JarFile(f);
			JarOutputStream jos = null;
			jos = new JarOutputStream(new FileOutputStream(outputDir.getPath()
					+ File.separator + f.getName()));
			Enumeration<JarEntry> entries = jar.entries();
			while (entries.hasMoreElements()) {
				JarEntry e = entries.nextElement();
				if (e.getName().endsWith(".class")) {
					{
						if (ANALYZE_ONLY)
							analyzeClass(jar.getInputStream(e));
						else
							try {
								JarEntry outEntry = new JarEntry(e.getName());
								jos.putNextEntry(outEntry);
								byte[] clazz = instrumentClass(f.getAbsolutePath(),
										jar.getInputStream(e), true);
								if (clazz == null) {
									System.out.println("Failed to instrument " + e.getName()
											+ " in " + f.getName());
									InputStream is = jar.getInputStream(e);
									byte[] buffer = new byte[1024];
									while (true) {
										int count = is.read(buffer);
										if (count == -1)
											break;
										jos.write(buffer, 0, count);
									}
								} else {
									jos.write(clazz);
								}
								jos.closeEntry();
							} catch (ZipException ex) {
								ex.printStackTrace();
								continue;
							}

					}

				} else {
					JarEntry outEntry = new JarEntry(e.getName());
					if (e.isDirectory()) {
						try {
							jos.putNextEntry(outEntry);
							jos.closeEntry();
						} catch (ZipException exxx) {
							System.out.println("Ignoring exception: " + exxx);
						}
					} else if (e.getName().startsWith("META-INF")
							&& (e.getName().endsWith(".SF") || e.getName().endsWith(".RSA"))) {
						// don't copy this
					} else if (e.getName().equals("META-INF/MANIFEST.MF")) {
						Scanner s = new Scanner(jar.getInputStream(e));
						jos.putNextEntry(outEntry);

						String curPair = "";
						while (s.hasNextLine()) {
							String line = s.nextLine();
							if (line.equals("")) {
								curPair += "\n";
								if (!curPair.contains("SHA1-Digest:"))
									jos.write(curPair.getBytes());
								curPair = "";
							} else {
								curPair += line + "\n";
							}
						}
						s.close();
						// jos.write("\n".getBytes());
						jos.closeEntry();
					} else {
						try {
							jos.putNextEntry(outEntry);
							InputStream is = jar.getInputStream(e);
							byte[] buffer = new byte[1024];
							while (true) {
								int count = is.read(buffer);
								if (count == -1)
									break;
								jos.write(buffer, 0, count);
							}
							jos.closeEntry();
						} catch (ZipException ex) {
							if (!ex.getMessage().contains("duplicate entry")) {
								ex.printStackTrace();
								System.out
										.println("Ignoring above warning from improper source zip...");
							}
						}
					}

				}

			}
			if (jos != null) {
				jos.close();

			}
			jar.close();
		} catch (Exception e) {
			System.err.println("Unable to process jar: " + f.getAbsolutePath());
			e.printStackTrace();
			// logger.log(Level.SEVERE, "Unable to process jar: " +
			// f.getAbsolutePath(), e);
			File dest = new File(outputDir.getPath() + File.separator + f.getName());
			FileChannel source = null;
			FileChannel destination = null;

			try {
				source = new FileInputStream(f).getChannel();
				destination = new FileOutputStream(dest).getChannel();
				destination.transferFrom(source, 0, source.size());
			} catch (Exception ex) {
				System.err.println("Unable to copy file: " + f.getAbsolutePath());
				ex.printStackTrace();
				// System.exit(-1);
			} finally {
				if (source != null) {
					try {
						source.close();
					} catch (IOException e2) {
						// TODO Auto-generated catch block
						e2.printStackTrace();
					}
				}
				if (destination != null) {
					try {
						destination.close();
					} catch (IOException e2) {
						// TODO Auto-generated catch block
						e2.printStackTrace();
					}
				}
			}
			// System.exit(-1);
		}

	}

	private static void processZip(File f, File outputDir) {
		try {
			// @SuppressWarnings("resource")
			ZipFile zip = new ZipFile(f);
			ZipOutputStream zos = null;
			zos = new ZipOutputStream(new FileOutputStream(outputDir.getPath()
					+ File.separator + f.getName()));
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry e = entries.nextElement();

				if (e.getName().endsWith(".class")) {
					{
						if (ANALYZE_ONLY)
							analyzeClass(zip.getInputStream(e));
						else {
							ZipEntry outEntry = new ZipEntry(e.getName());
							zos.putNextEntry(outEntry);

							byte[] clazz = instrumentClass(f.getAbsolutePath(),
									zip.getInputStream(e), true);
							if (clazz == null) {
								InputStream is = zip.getInputStream(e);
								byte[] buffer = new byte[1024];
								while (true) {
									int count = is.read(buffer);
									if (count == -1)
										break;
									zos.write(buffer, 0, count);
								}
							} else
								zos.write(clazz);
							zos.closeEntry();
						}
					}

				} else if (e.getName().endsWith(".jar") && !e.getName().endsWith("staccato.jar")) {
					ZipEntry outEntry = new ZipEntry(e.getName());
					// jos.putNextEntry(outEntry);
					// try {
					// processJar(jar.getInputStream(e), jos);
					// jos.closeEntry();
					// } catch (FileNotFoundException e1) {
					// // TODO Auto-generated catch block
					// e1.printStackTrace();
					// }

					File tmp = new File("/tmp/classfile");
					if (tmp.exists())
						tmp.delete();
					FileOutputStream fos = new FileOutputStream(tmp);
					byte buf[] = new byte[1024];
					int len;
					InputStream is = zip.getInputStream(e);
					while ((len = is.read(buf)) > 0) {
						fos.write(buf, 0, len);
					}
					is.close();
					fos.close();
					// System.out.println("Done reading");
					File tmp2 = new File("tmp2");
					if (!tmp2.exists())
						tmp2.mkdir();
					processJar(tmp, new File("tmp2"));

					zos.putNextEntry(outEntry);
					is = new FileInputStream("tmp2/classfile");
					byte[] buffer = new byte[1024];
					while (true) {
						int count = is.read(buffer);
						if (count == -1)
							break;
						zos.write(buffer, 0, count);
					}
					is.close();
					zos.closeEntry();
					// jos.closeEntry();
				} else {
					ZipEntry outEntry = new ZipEntry(e.getName());
					if (e.isDirectory()) {
						try {
							zos.putNextEntry(outEntry);
							zos.closeEntry();
						} catch (ZipException exxxx) {
							System.out.println("Ignoring exception: " + exxxx.getMessage());
						}
					} else if (e.getName().startsWith("META-INF")
							&& (e.getName().endsWith(".SF") || e.getName().endsWith(".RSA"))) {
						// don't copy this
					} else if (e.getName().equals("META-INF/MANIFEST.MF")) {
						Scanner s = new Scanner(zip.getInputStream(e));
						zos.putNextEntry(outEntry);

						String curPair = "";
						while (s.hasNextLine()) {
							String line = s.nextLine();
							if (line.equals("")) {
								curPair += "\n";
								if (!curPair.contains("SHA1-Digest:"))
									zos.write(curPair.getBytes());
								curPair = "";
							} else {
								curPair += line + "\n";
							}
						}
						s.close();
						zos.write("\n".getBytes());
						zos.closeEntry();
					} else {
						zos.putNextEntry(outEntry);
						InputStream is = zip.getInputStream(e);
						byte[] buffer = new byte[1024];
						while (true) {
							int count = is.read(buffer);
							if (count == -1)
								break;
							zos.write(buffer, 0, count);
						}
						zos.closeEntry();
					}
				}

			}
			zos.close();
			zip.close();
		} catch (Exception e) {
			System.err.println("Unable to process zip: " + f.getAbsolutePath());
			e.printStackTrace();
			File dest = new File(outputDir.getPath() + File.separator + f.getName());
			FileChannel source = null;
			FileChannel destination = null;

			try {
				source = new FileInputStream(f).getChannel();
				destination = new FileOutputStream(dest).getChannel();
				destination.transferFrom(source, 0, source.size());
			} catch (Exception ex) {
				System.err.println("Unable to copy zip: " + f.getAbsolutePath());
				ex.printStackTrace();
				// System.exit(-1);
			} finally {
				if (source != null) {
					try {
						source.close();
					} catch (IOException e2) {
						// TODO Auto-generated catch block
						e2.printStackTrace();
					}
				}
				if (destination != null) {
					try {
						destination.close();
					} catch (IOException e2) {
						// TODO Auto-generated catch block
						e2.printStackTrace();
					}
				}
			}
		}

	}

	public static boolean shouldCallUninstAlways(String owner, String name,
			String desc) {
		if (name.equals("writeArray") && owner.equals("java/io/ObjectOutputStream"))
			return true;
		return false;
	}

	public static boolean isIgnoredMethod(String owner, String name, String desc) {
		if(Configuration.taintTagFactory.isIgnoredMethod(owner, name, desc)) {
			return true;
		}
		if (name.equals("wait") && desc.equals("(J)V"))
			return true;
		if (name.equals("wait") && desc.equals("(JI)V"))
			return true;
		return false;
	}

}