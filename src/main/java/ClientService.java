import it.tdlight.common.ExceptionHandler;
import it.tdlight.common.Init;
import it.tdlight.common.ResultHandler;
import it.tdlight.common.TelegramClient;
import it.tdlight.common.utils.CantLoadLibrary;
import it.tdlight.jni.TdApi;
import it.tdlight.tdlight.ClientManager;

import java.io.IOError;
import java.io.IOException;
import java.util.Scanner;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ClientService {
    // todo default client fields
    private static TdApi.AuthorizationState authorizationState = null;
    private static final Lock authorizationLock = new ReentrantLock();
    private static final Condition gotAuthorization = authorizationLock.newCondition();
    private static final String newLine = System.getProperty("line.separator");
    private static TelegramClient client = null;
    private boolean haveAuthorization = false;
    private boolean needQuit = false;


    public ClientService() {
        try {
            Init.start();
        } catch (CantLoadLibrary cantLoadLibrary) {
            cantLoadLibrary.printStackTrace();
        }
        client = ClientManager.create();
        client.initialize(new RegHandler(), new ErrorHandler(), new ErrorHandler());

        client.execute(new TdApi.SetLogVerbosityLevel(0));
        // disable TDLib log
        if (client.execute(new TdApi.SetLogStream(new TdApi.LogStreamFile("tdlib.log", 1 << 27, false))) instanceof TdApi.Error) {
            throw new IOError(new IOException("Write access to the current directory is required"));
        }
        // await authorization
        authorizationLock.lock();
        try {
            while (!haveAuthorization) {
                try {
                    gotAuthorization.await();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        } finally {
            authorizationLock.unlock();
        }
    }


    public void online() {

//        AtomicBoolean work = new AtomicBoolean(false);

        while (true) {

            TdApi.SetOption option = new TdApi.SetOption();
            option.name = "online";
            option.value = new TdApi.OptionValueBoolean(true);

            client.send(option, System.out::println);
            try {
                Thread.sleep(1000 * 40);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            client.send(option, System.out::println);
            try {
                Thread.sleep(1000 * 40);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }


    }


    private static class EndClient implements ResultHandler {
        @Override
        public void onResult(TdApi.Object object) {
        }
    }


    private static class ErrorHandler implements ExceptionHandler {

        @Override
        public void onException(Throwable e) {
            e.printStackTrace();
        }
    }


    private class RegHandler implements ResultHandler {
        @Override
        public void onResult(TdApi.Object object) {
            if (object.getConstructor() == TdApi.UpdateAuthorizationState.CONSTRUCTOR) {
                onAuthorizationStateUpdated(((TdApi.UpdateAuthorizationState) object).authorizationState);
            }
        }

        private void onAuthorizationStateUpdated(TdApi.AuthorizationState authorizationState) {
            if (authorizationState != null) {
                ClientService.authorizationState = authorizationState;
            }
            switch (ClientService.authorizationState.getConstructor()) {
                case TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR:
                    TdApi.TdlibParameters parameters = new TdApi.TdlibParameters();
                    parameters.databaseDirectory = "tdlib";
                    parameters.useMessageDatabase = true;
                    parameters.useSecretChats = true;
                    parameters.apiId = 94575;
                    parameters.apiHash = "a3406de8d171bb422bb6ddf3bbd800e2";
                    parameters.systemLanguageCode = "en";
                    parameters.deviceModel = "Desktop";
                    parameters.applicationVersion = "1.0";
                    parameters.enableStorageOptimizer = true;

                    client.send(new TdApi.SetTdlibParameters(parameters), object -> System.out.println(object.toString()));
                    break;
                case TdApi.AuthorizationStateWaitEncryptionKey.CONSTRUCTOR:
                    client.send(new TdApi.CheckDatabaseEncryptionKey(), object -> System.out.println(object.toString()));
                    break;
                case TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR: {
                    String phoneNumber = getString("Please enter phone number: ", true);
                    client.send(new TdApi.SetAuthenticationPhoneNumber(phoneNumber, null), object -> System.out.println(object.toString()));
                    break;
                }
                case TdApi.AuthorizationStateWaitOtherDeviceConfirmation.CONSTRUCTOR: {
                    String link = ((TdApi.AuthorizationStateWaitOtherDeviceConfirmation) ClientService.authorizationState).link;
                    System.out.println("Please confirm this login link on another device: " + link);
                    break;
                }
                case TdApi.AuthorizationStateWaitCode.CONSTRUCTOR: {
                    String code = getString("Please enter authentication code: ", true);
                    client.send(new TdApi.CheckAuthenticationCode(code), object -> System.out.println(object.toString()));
                    break;
                }
                case TdApi.AuthorizationStateWaitRegistration.CONSTRUCTOR: {
                    String firstName = getString("Please enter your first name: ", true);
                    String lastName = getString("Please enter your last name: ", true);
                    client.send(new TdApi.RegisterUser(firstName, lastName), object -> System.out.println(object.toString()));
                    break;
                }
                case TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR: {
                    String password = getString("Please enter password: ", true);
                    client.send(new TdApi.CheckAuthenticationPassword(password), object -> System.out.println(object.toString()));
                    break;
                }
                case TdApi.AuthorizationStateReady.CONSTRUCTOR:
                    haveAuthorization = true;
                    authorizationLock.lock();
                    try {
                        gotAuthorization.signal();
                    } finally {
                        authorizationLock.unlock();
                    }
                    break;
                case TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR:
                    haveAuthorization = false;
                    System.out.println("Logging out");
                    break;
                case TdApi.AuthorizationStateClosing.CONSTRUCTOR:
                    haveAuthorization = false;
                    System.out.println("Closing");
                    break;
                case TdApi.AuthorizationStateClosed.CONSTRUCTOR:
                    System.out.println("Closed");
                    if (!needQuit) {
                        client = ClientManager.create(); // recreate client after previous has closed
                        client.initialize(new EndClient(), new ErrorHandler(), new ErrorHandler());
                    }
                    break;
                default:
                    System.err.println("Unsupported authorization state:" + newLine + ClientService.authorizationState);
            }
        }

        public String getString(String ask, boolean oneLine) {
            if (oneLine) {
                System.out.print(ask + ":  ");
            } else {
                System.out.println(ask);
            }
            Scanner sc = new Scanner(System.in);
            String res = sc.nextLine();
            return res;
        }

    }
}
