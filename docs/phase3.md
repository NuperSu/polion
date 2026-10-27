# Ownership and portfolio scopes

Owners, accounts and tracking targets are distinct. Accounts form owner-local acyclic hierarchies and can represent wallets or groups. Portfolios select owners and account subtrees; an empty selection means all. Each target has its own enabled state, labels and tags. Disabling collection does not erase the target or its existing holdings.

Connectors provide canonical target identities, including an explicit network and target kind. Core does not infer chain-specific address case normalization. Scope resolution groups aliases by identity and exposes all ownership claims, including claims outside the selected portfolio, so ambiguity is visible. Wallet groups and leaf identities are returned separately; callers count each leaf once and must not add a wallet aggregate to its leaves. Multiple wallet claims on a leaf are exposed as ambiguous. Discovery, durable storage, valuation and balance projection are separate capabilities.
