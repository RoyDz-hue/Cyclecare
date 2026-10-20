export interface AdminUser {
  id: string;
  email: string;
  role: string;
}

export interface Profile {
  user_id: string;
  display_name?: string;
  avatar_url?: string;
  bio?: string;
  rank_badge?: string;
  active_theme?: string;
  status: string;
  created_at: string;
}

export interface EconomyProfile {
  user_id: string;
  coin_balance: number;
  lifetime_earned: number;
  lifetime_spent: number;
  tier: string;
  rank: string;
  unlocked_items: string[];
  status: string;
}

export interface CoinTransaction {
  id: string;
  user_id: string;
  action: string;
  delta: number;
  balance_after: number;
  metadata?: Record<string, unknown>;
  created_at: string;
}

export interface ChatTopic {
  id: string;
  title: string;
  description: string;
  category: string;
  coin_gate: number;
  is_active: boolean;
  created_at: string;
}

export interface SafetyEvent {
  id: string;
  user_id: string;
  level: 'URGENT' | 'HIGH' | 'MEDIUM' | 'LOW';
  trigger_key: string;
  resolved_at?: string;
  created_at: string;
}

export interface FeatureFlag {
  key: string;
  value: string;
  description?: string;
}

export interface CareArticleItem {
  id: string;
  question: string;
  topic: string;
  whatText: string;
  understandText: string;
  careText: string;
  watchText: string;
  escalateText: string;
  relevantPhases: string[];
}
