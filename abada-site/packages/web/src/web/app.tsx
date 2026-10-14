import { Route, Switch } from "wouter";
import Index from "./pages/index";
import VsCamundaPage from "./pages/vs-camunda";
import InvestorsPage from "./pages/investors";
import ThanksPage from "./pages/thanks";
import { Provider } from "./components/provider";

function App() {
  return (
    <Provider>
      <Switch>
        <Route path="/" component={Index} />
        <Route path="/vs-camunda" component={VsCamundaPage} />
        <Route path="/investors" component={InvestorsPage} />
        <Route path="/thanks" component={ThanksPage} />
      </Switch>
    </Provider>
  );
}

export default App;
