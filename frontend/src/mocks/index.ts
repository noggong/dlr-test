import doneJson from './done.json';
import groupJson from './group.json';
import quizJson from './quiz.json';
import resultJson from './result.json';
import signupJson from './signup.json';
import soloJson from './solo.json';
import waitingJson from './waiting.json';
import type {
  DoneScreen,
  GroupInfo,
  QuizScreen,
  ResultScreen,
  SignupData,
  SoloScreen,
  WaitingScreen,
} from './types';

// JSON 은 리터럴 타입(진행도 -1~3 · 상태 3색)을 잃으므로 여기서 한 번 타입을 입힌다.
export const groups = groupJson as Record<'main' | 'solo', GroupInfo>;
export const quiz = quizJson as Record<string, QuizScreen>;
export const done = doneJson as Record<string, DoneScreen>;
export const result = resultJson as Record<string, ResultScreen>;
export const waiting = waitingJson as Record<string, WaitingScreen>;
export const solo = soloJson as SoloScreen;
export const signup = signupJson as SignupData;
